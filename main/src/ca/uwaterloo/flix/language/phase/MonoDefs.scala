/*
 * Copyright 2017 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.Ast.Modifiers
import ca.uwaterloo.flix.language.ast.LoweredAst._
import ca.uwaterloo.flix.language.ast.{Ast, Kind, LoweredAst, RigidityEnv, Scheme, Symbol, Type, TypeConstructor}
import ca.uwaterloo.flix.language.phase.unification.{EqualityEnvironment, Substitution, Unification}
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import ca.uwaterloo.flix.util.collection.ListMap
import ca.uwaterloo.flix.util.{InternalCompilerException, ParOps, Result}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.immutable.SortedSet
import scala.collection.mutable

/**
  * Monomorphization is a whole-program compilation strategy that replaces every reference to a parametric function with
  * a reference to a non-parametric version (of that function) specialized to the concrete types of the reference.
  *
  * For example, the polymorphic program:
  *
  * -   def fst[a, b](p: (a, b)): a = let (x, y) = p ; x
  * -   def f: Bool = fst((true, 'a'))
  * -   def g: Int32 = fst((42, "foo"))
  *
  * is, roughly speaking, translated to:
  *
  * -   def fst$1(p: (Bool, Char)): Bool = let (x, y) = p ; x
  * -   def fst$2(p: (Int32, Str)): Int32 = let (x, y) = p ; x
  * -   def f: Bool = fst$1((true, 'a'))
  * -   def g: Bool = fst$2((42, "foo"))
  *
  * At a high-level, monomorphization works as follows:
  *
  * 1. We maintain a queue of functions and the concrete types they must be specialized to.
  * 2. We populate the queue by specialization of non-parametric function definitions and other top-level expressions.
  * 3. We iteratively extract a function from the queue and specialize it:
  *    a. We replace every type variable appearing anywhere in the definition by its concrete type.
  *       b. We create new fresh local variable symbols (since the function is effectively being copied).
  *       c. We enqueue (or re-used) other functions referenced by the current function which require specialization.
  *       4. We reconstruct the AST from the specialized functions and remove all parametric functions.
  */
object MonoDefs {

  /**
    * A strict substitution is similar to a regular substitution except that free type variables are replaced by the
    * Unit type. In other words, when performing a type substitution if there is no requirement on a polymorphic type
    * we assume it to be Unit. This is safe since otherwise the type would not be polymorphic after type-inference.
    */
  private case class StrictSubstitution(s: Substitution, eqEnv: ListMap[Symbol.AssocTypeSym, Ast.AssocTypeDef])(implicit flix: Flix) {

    private def default(tpe0: Type): Type = tpe0.kind match {
      case Kind.Eff =>
        // If an effect variable is free, we may assume its Pure due to the subst. lemma.
        Type.Pure
      case Kind.RecordRow => Type.RecordRowEmpty
      case Kind.SchemaRow => Type.SchemaRowEmpty
      case Kind.CaseSet(sym) => Type.Cst(TypeConstructor.CaseSet(SortedSet.empty, sym), tpe0.loc)
      case _ => Type.Unit
    }

    /**
      * Applies `this` substitution to the given type `tpe`.
      *
      * NB: Applies the substitution first, then replaces every type variable with the unit type.
      */
    def apply(tpe0: Type): Type = {
      // NB: The order of cases has been determined by code coverage analysis.
      def visit(t: Type): Type =
        t match {
          // When a substitution is performed, eliminate variables from the result.
          case x: Type.Var => s.m.get(x.sym) match {
            case Some(tpe) => tpe.map(default)
            case None => default(t)
          }
          // Erase concrete effects like Print.
          case Type.Cst(TypeConstructor.Effect(_), _) => Type.EffUniv
          case Type.Cst(_, _) => t
          case Type.Apply(t1, t2, loc) =>
            val y = visit(t2)
            visit(t1) match {
              // Simplify boolean equations.
              case Type.Cst(TypeConstructor.Complement, _) => Type.mkComplement(y, loc)
              case Type.Apply(Type.Cst(TypeConstructor.Union, _), x, _) => Type.mkUnion(x, y, loc)
              case Type.Apply(Type.Cst(TypeConstructor.Intersection, _), x, _) => Type.mkIntersection(x, y, loc)

              case Type.Cst(TypeConstructor.CaseComplement(sym), _) => Type.mkCaseComplement(y, sym, loc)
              case Type.Apply(Type.Cst(TypeConstructor.CaseIntersection(sym), _), x, _) => Type.mkCaseIntersection(x, y, sym, loc)
              case Type.Apply(Type.Cst(TypeConstructor.CaseUnion(sym), _), x, _) => Type.mkCaseUnion(x, y, sym, loc)

              // Else just apply
              case x => Type.Apply(x, y, loc)
            }
          case Type.Alias(sym, args0, tpe0, loc) =>
            val args = args0.map(visit)
            val tpe = visit(tpe0)
            Type.Alias(sym, args, tpe, loc)

          // Perform reduction on associated types.
          case Type.AssocType(cst, arg0, _, loc) =>
            val arg = visit(arg0)
            EqualityEnvironment.reduceAssocType(cst, arg, eqEnv) match {
              case Ok(t) => t
              case Err(_) => throw InternalCompilerException("unexpected associated type reduction failure", loc)
            }
        }

      visit(tpe0)
    }

    /**
      * Adds the given mapping to the substitution.
      */
    def +(kv: (Symbol.KindedTypeVarSym, Type)): StrictSubstitution = kv match {
      case (tvar, tpe) => StrictSubstitution(s ++ Substitution.singleton(tvar, tpe), eqEnv)
    }

    /**
      * Returns the non-strict version of this substitution.
      */
    def nonStrict: Substitution = s
  }

  /**
    * Holds the mutable data used throughout monomorphization.
    *
    * This class is thread-safe.
    */
  private class Context() {

    /**
      * A function-local queue of pending (fresh symbol, function definition, and substitution)-triples.
      *
      * For example, if the queue contains the entry:
      *
      * -   (f$1, f, [a -> Int])
      *
      * it means that the function definition f should be specialized w.r.t. the map [a -> Int] under the fresh name f$1.
      *
      * Note: We use a concurrent linked queue (which is non-blocking) so threads can enqueue items without contention.
      */
    private val defQueue: ConcurrentLinkedQueue[(Symbol.DefnSym, Def, StrictSubstitution)] = new ConcurrentLinkedQueue

    /**
      * Returns `true` if the queue is non-empty.
      *
      * Note: This is not synchronized.
      */
    def nonEmpty: Boolean = {
      !defQueue.isEmpty
    }

    /**
      * Enqueues the given symbol, def, and substitution triple.
      *
      * Note: This is not synchronized.
      */
    def enqueue(sym: Symbol.DefnSym, defn: Def, subst: StrictSubstitution): Unit = {
      defQueue.add((sym, defn, subst))
    }

    /**
      * Dequeues an element from the queue.
      *
      * Note: This is not synchronized.
      */
    def dequeueAll: Array[(Symbol.DefnSym, Def, StrictSubstitution)] = {
      val r = defQueue.toArray(Array.empty[(Symbol.DefnSym, Def, StrictSubstitution)])
      defQueue.clear()
      r
    }

    /**
      * A function-local map from a symbol and a concrete type to the fresh symbol for the specialized version of that function.
      *
      * For example, if the function:
      *
      * -   def fst[a, b](x: a, y: b): a = ...
      *
      * has been specialized w.r.t. to `Int` and `Str` then this map will contain an entry:
      *
      * -   (fst, (Int, Str) -> Int) -> fst$1
      */
    private val def2def: mutable.Map[(Symbol.DefnSym, Type), Symbol.DefnSym] = mutable.Map.empty

    /**
      * Optionally returns the specialized def symbol for the given symbol `sym` and type `tpe`.
      */
    def getDef2Def(sym: Symbol.DefnSym, tpe: Type): Option[Symbol.DefnSym] = synchronized {
      def2def.get((sym, tpe))
    }

    /**
      * Adds a new def2def binding for the given symbol `sym1` and type `tpe`.
      */
    def putDef2Def(sym1: Symbol.DefnSym, tpe: Type, sym2: Symbol.DefnSym): Unit = synchronized {
      def2def.put((sym1, tpe), sym2)
    }

    /**
      * A map used to collect specialized definitions, etc.
      */
    private val specializedDefns: mutable.Map[Symbol.DefnSym, LoweredAst.Def] = mutable.Map.empty

    /**
      * Adds a new specialized definition for the given def symbol `sym`.
      */
    def putSpecializedDef(sym: Symbol.DefnSym, defn: LoweredAst.Def): Unit = synchronized {
      specializedDefns.put(sym, defn)
    }

    /**
      * Returns the specialized definitions as an immutable map.
      */
    def toMap: Map[Symbol.DefnSym, LoweredAst.Def] = synchronized {
      specializedDefns.toMap
    }
  }

  /**
    * Performs monomorphization of the given AST `root`.
    */
  def run(root: Root)(implicit flix: Flix): Root = flix.phase("MonoDefs") {

    implicit val r: Root = root
    implicit val ctx: Context = new Context()
    val empty = StrictSubstitution(Substitution.empty, root.eqEnv)

    /*
     * Collect all non-parametric function definitions.
     */
    val nonParametricDefns = root.defs.filter {
      case (_, defn) => defn.spec.tparams.isEmpty
    }

    /*
     * Perform specialization of all non-parametric function definitions.
     *
     * We perform specialization in parallel.
     *
     * This will enqueue additional functions for specialization.
     */
    ParOps.parMap(nonParametricDefns) {
      case (sym, defn) =>
        // We use an empty substitution because the defs are non-parametric.
        mkFreshDefn(sym, defn, empty)
    }

    /*
     * Perform function specialization until the queue is empty.
     *
     * We perform specialization in parallel along the frontier, i.e. each frontier is done in parallel.
     */
    while (ctx.nonEmpty) {
      // Extract a function from the queue and specializes it w.r.t. its substitution.
      val queue = ctx.dequeueAll
      ParOps.parMap(queue) {
        case (freshSym, defn, subst) => mkFreshDefn(freshSym, defn, subst)
      }
    }

    // Reassemble the AST.
    root.copy(
      defs = ctx.toMap,
      classes = Map.empty,
      instances = Map.empty,
      sigs = Map.empty
    )
  }

  /**
    * Adds a specialized def for the given symbol `freshSym` and def `defn` with the given substitution `subst`.
    */
  private def mkFreshDefn(freshSym: Symbol.DefnSym, defn: Def, subst: StrictSubstitution)(implicit ctx: Context, root: Root, flix: Flix): Unit = {
    // Specialize the formal parameters and introduce fresh local variable symbols.
    val (fparams, env0) = specializeFormalParams(defn.spec.fparams, subst)

    // Specialize the body expression.
    val specializedExp = visitExp(defn.exp, env0, subst)

    // Reassemble the definition.
    // NB: Removes the type parameters as the function is now monomorphic.
    val spec0 = defn.spec
    val spec = Spec(
      spec0.doc,
      spec0.ann,
      spec0.mod,
      Nil,
      fparams,
      Scheme(Nil, Nil, Nil, subst(defn.spec.declaredScheme.base)),
      subst(spec0.retTpe),
      subst(spec0.eff),
      spec0.tconstrs,
      spec0.loc
    )
    val specializedDefn = defn.copy(sym = freshSym, spec = spec, exp = specializedExp)

    // Save the specialized function.
    ctx.putSpecializedDef(freshSym, specializedDefn)
  }

  /**
    * Performs specialization of the given expression `exp0` under the environment `env0` w.r.t. the given substitution `subst`.
    *
    * Replaces every reference to a parametric function with a reference to its specialized version.
    *
    * Replaces every local variable symbol with a fresh local variable symbol.
    *
    * If a specialized version of a function does not yet exists, a fresh symbol is created for it, and the
    * definition and substitution is enqueued.
    */
  private def visitExp(exp0: Expr, env0: Map[Symbol.VarSym, Symbol.VarSym], subst: StrictSubstitution)(implicit ctx: Context, root: Root, flix: Flix): Expr = exp0 match {
    case Expr.Var(sym, tpe, loc) =>
      Expr.Var(env0(sym), subst(tpe), loc)

    case Expr.Def(sym, tpe, loc) =>
      /*
       * !! This is where all the magic happens !!
       */
      val newSym = specializeDefSym(sym, subst(tpe))
      Expr.Def(newSym, subst(tpe), loc)

    case Expr.Sig(sym, tpe, loc) =>
      val newSym = specializeSigSym(sym, subst(tpe))
      Expr.Def(newSym, subst(tpe), loc)

    case Expr.Cst(cst, tpe, loc) =>
      Expr.Cst(cst, subst(tpe), loc)

    case Expr.Lambda(fparam, exp, tpe, loc) =>
      val (p, env1) = specializeFormalParam(fparam, subst)
      val e = visitExp(exp, env0 ++ env1, subst)
      Expr.Lambda(p, e, subst(tpe), loc)

    case Expr.Apply(exp, exps, tpe, eff, loc) =>
      val e = visitExp(exp, env0, subst)
      val es = exps.map(visitExp(_, env0, subst))
      Expr.Apply(e, es, subst(tpe), subst(eff), loc)

    case Expr.ApplyAtomic(op, exps, tpe, eff, loc) =>
      val es = exps.map(visitExp(_, env0, subst))
      Expr.ApplyAtomic(op, es, subst(tpe), subst(eff), loc)

    case Expr.Let(sym, mod, exp1, exp2, tpe, eff, loc) =>
      // Generate a fresh symbol for the let-bound variable.
      val freshSym = Symbol.freshVarSym(sym)
      val env1 = env0 + (sym -> freshSym)
      Expr.Let(freshSym, mod, visitExp(exp1, env0, subst), visitExp(exp2, env1, subst), subst(tpe), subst(eff), loc)

    case Expr.LetRec(sym, mod, exp1, exp2, tpe, eff, loc) =>
      // Generate a fresh symbol for the let-bound variable.
      val freshSym = Symbol.freshVarSym(sym)
      val env1 = env0 + (sym -> freshSym)
      Expr.LetRec(freshSym, mod, visitExp(exp1, env1, subst), visitExp(exp2, env1, subst), subst(tpe), subst(eff), loc)

    case Expr.Scope(sym, regionVar, exp, tpe, eff, loc) =>
      val freshSym = Symbol.freshVarSym(sym)
      val env1 = env0 + (sym -> freshSym)
      // forcedly mark the region variable as Impure inside the region
      val subst1 = StrictSubstitution(subst.s.unbind(regionVar.sym), subst.eqEnv)
      val subst2 = subst1 + (regionVar.sym -> Type.Impure)
      Expr.Scope(freshSym, regionVar, visitExp(exp, env1, subst2), subst(tpe), subst(eff), loc)

    case Expr.IfThenElse(exp1, exp2, exp3, tpe, eff, loc) =>
      val e1 = visitExp(exp1, env0, subst)
      val e2 = visitExp(exp2, env0, subst)
      val e3 = visitExp(exp3, env0, subst)
      Expr.IfThenElse(e1, e2, e3, subst(tpe), subst(eff), loc)

    case Expr.Stm(exp1, exp2, tpe, eff, loc) =>
      val e1 = visitExp(exp1, env0, subst)
      val e2 = visitExp(exp2, env0, subst)
      Expr.Stm(e1, e2, subst(tpe), subst(eff), loc)

    case Expr.Discard(exp, eff, loc) =>
      val e = visitExp(exp, env0, subst)
      Expr.Discard(e, subst(eff), loc)

    case Expr.Match(exp, rules, tpe, eff, loc) =>
      val rs = rules map {
        case MatchRule(pat, guard, body) =>
          val (p, env1) = visitPat(pat, subst)
          val extendedEnv = env0 ++ env1
          val g = guard.map(visitExp(_, extendedEnv, subst))
          val b = visitExp(body, extendedEnv, subst)
          MatchRule(p, g, b)
      }
      Expr.Match(visitExp(exp, env0, subst), rs, subst(tpe), subst(eff), loc)

    case Expr.TypeMatch(exp, rules, tpe, _, loc) =>
      // use the non-strict substitution
      // to allow free type variables to match with anything
      val expTpe = subst.nonStrict(exp.tpe)
      // make the tvars in `exp`'s type rigid
      // so that Nil: List[x%123] can only match List[_]
      val renv = expTpe.typeVars.foldLeft(RigidityEnv.empty) {
        case (acc, Type.Var(sym, _)) => acc.markRigid(sym)
      }
      rules.iterator.flatMap {
        case TypeMatchRule(sym, t, body0) =>
          // try to unify
          Unification.unifyTypes(expTpe, subst.nonStrict(t), renv) match {
            // Case 1: types don't unify; just continue
            case Result.Err(_) => None
            // Case 2: types unify; use the substitution in the body
            case Result.Ok((caseSubst, econstrs)) => // TODO ASSOC-TYPES consider econstrs
              // visit the base expression under the initial environment
              val e = visitExp(exp, env0, subst)
              // Generate a fresh symbol for the let-bound variable.
              val freshSym = Symbol.freshVarSym(sym)
              val env1 = env0 + (sym -> freshSym)
              val subst1 = caseSubst @@ subst.nonStrict
              // visit the body under the extended environment
              val body = visitExp(body0, env1, StrictSubstitution(subst1, root.eqEnv))
              val eff = Type.mkUnion(exp.eff, body0.eff, loc.asSynthetic)
              Some(Expr.Let(freshSym, Modifiers.Empty, e, body, StrictSubstitution(subst1, root.eqEnv).apply(tpe), subst1(eff), loc))
          }
      }.next() // We are safe to get next() because the last case will always match

    case Expr.VectorLit(exps, tpe, eff, loc) =>
      val es = exps.map(visitExp(_, env0, subst))
      Expr.VectorLit(es, subst(tpe), subst(eff), loc)

    case Expr.VectorLoad(exp1, exp2, tpe, eff, loc) =>
      val e1 = visitExp(exp1, env0, subst)
      val e2 = visitExp(exp2, env0, subst)
      Expr.VectorLoad(e1, e2, subst(tpe), subst(eff), loc)

    case Expr.VectorLength(exp, loc) =>
      val e = visitExp(exp, env0, subst)
      Expr.VectorLength(e, loc)

    case Expr.Ascribe(exp, tpe, eff, loc) =>
      val e = visitExp(exp, env0, subst)
      Expr.Ascribe(e, subst(tpe), subst(eff), loc)

    case Expr.Cast(exp, _, _, tpe, eff, loc) =>
      // We drop the declaredType and declaredEff here.
      val e = visitExp(exp, env0, subst)
      Expr.Cast(e, None, None, subst(tpe), subst(eff), loc)

    case Expr.TryCatch(exp, rules, tpe, eff, loc) =>
      val e = visitExp(exp, env0, subst)
      val rs = rules map {
        case CatchRule(sym, clazz, body) =>
          // Generate a fresh symbol.
          val freshSym = Symbol.freshVarSym(sym)
          val env1 = env0 + (sym -> freshSym)
          val b = visitExp(body, env1, subst)
          CatchRule(freshSym, clazz, b)
      }
      Expr.TryCatch(e, rs, subst(tpe), subst(eff), loc)

    case Expr.TryWith(exp, effect, rules, tpe, eff, loc) =>
      val e = visitExp(exp, env0, subst)
      val rs = rules map {
        case HandlerRule(op, fparams0, body0) =>
          val (fparams, fparamEnv) = specializeFormalParams(fparams0, subst)
          val env1 = env0 ++ fparamEnv
          val body = visitExp(body0, env1, subst)
          HandlerRule(op, fparams, body)
      }
      Expr.TryWith(e, effect, rs, subst(tpe), subst(eff), loc)

    case Expr.Do(op, exps, tpe, eff, loc) =>
      val es = exps.map(visitExp(_, env0, subst))
      Expr.Do(op, es, subst(tpe), subst(eff), loc)

    case Expr.NewObject(name, clazz, tpe, eff, methods0, loc) =>
      val methods = methods0.map(visitJvmMethod(_, env0, subst))
      Expr.NewObject(name, clazz, subst(tpe), subst(eff), methods, loc)

  }

  /**
    * Specializes the given pattern `p0` w.r.t. the current substitution.
    *
    * Returns the new pattern and a mapping from variable symbols to fresh variable symbols.
    */
  private def visitPat(p0: Pattern, subst: StrictSubstitution)(implicit flix: Flix): (Pattern, Map[Symbol.VarSym, Symbol.VarSym]) = p0 match {
    case Pattern.Wild(tpe, loc) => (Pattern.Wild(subst(tpe), loc), Map.empty)
    case Pattern.Var(sym, tpe, loc) =>
      // Generate a fresh variable symbol for the pattern-bound variable.
      val freshSym = Symbol.freshVarSym(sym)
      (Pattern.Var(freshSym, subst(tpe), loc), Map(sym -> freshSym))
    case Pattern.Cst(cst, tpe, loc) => (Pattern.Cst(cst, subst(tpe), loc), Map.empty)
    case Pattern.Tag(sym, pat, tpe, loc) =>
      val (p, env1) = visitPat(pat, subst)
      (Pattern.Tag(sym, p, subst(tpe), loc), env1)
    case Pattern.Tuple(elms, tpe, loc) =>
      val (ps, envs) = elms.map(visitPat(_, subst)).unzip
      (Pattern.Tuple(ps, subst(tpe), loc), envs.reduce(_ ++ _))
    case Pattern.Record(pats, pat, tpe, loc) =>
      val (ps, envs) = pats.map {
        case Pattern.Record.RecordLabelPattern(label, tpe1, pat1, loc1) =>
          val (p1, env1) = visitPat(pat1, subst)
          (Pattern.Record.RecordLabelPattern(label, subst(tpe1), p1, loc1), env1)
      }.unzip
      val (p, env1) = visitPat(pat, subst)
      val finalEnv = env1 :: envs
      (Pattern.Record(ps, p, subst(tpe), loc), finalEnv.reduce(_ ++ _))
    case Pattern.RecordEmpty(tpe, loc) => (Pattern.RecordEmpty(subst(tpe), loc), Map.empty)
  }

  /**
    * Specializes the given method `method` w.r.t. the current substitution.
    *
    * Returns the new method.
    */
  private def visitJvmMethod(method: JvmMethod, env0: Map[Symbol.VarSym, Symbol.VarSym], subst: StrictSubstitution)(implicit ctx: Context, root: Root, flix: Flix): JvmMethod = method match {
    case JvmMethod(ident, fparams0, exp0, tpe, eff, loc) =>
      val (fparams, env1) = specializeFormalParams(fparams0, subst)
      val exp = visitExp(exp0, env0 ++ env1, subst)
      JvmMethod(ident, fparams, exp, subst(tpe), subst(eff), loc)
  }

  /**
    * Returns the def symbol corresponding to the specialized symbol `sym` w.r.t. to the type `tpe`.
    */
  private def specializeDefSym(sym: Symbol.DefnSym, tpe: Type)(implicit ctx: Context, root: Root, flix: Flix): Symbol.DefnSym = {
    // Lookup the definition and its declared type.
    val defn = root.defs(sym)

    // Compute the erased type.
    val erasedType = eraseType(tpe)

    // Check if the function is non-polymorphic.
    if (defn.spec.tparams.isEmpty) {
      defn.sym
    } else {
      specializeDef(defn, erasedType)
    }
  }

  /**
    * Returns the def symbol corresponding to the specialized symbol `sym` w.r.t. to the type `tpe`.
    */
  private def specializeSigSym(sym: Symbol.SigSym, tpe0: Type)(implicit ctx: Context, root: Root, flix: Flix): Symbol.DefnSym = {
    // Perform erasure on the type
    val tpe = eraseType(tpe0)

    val sig = root.sigs(sym)

    // lookup the instance corresponding to this type
    val instances = root.instances(sig.sym.clazz)

    val defns = instances.flatMap {
      inst =>
        inst.defs.find {
          defn =>
            defn.sym.text == sig.sym.name && Unification.unifiesWith(defn.spec.declaredScheme.base, tpe, RigidityEnv.empty, root.eqEnv)
        }
    }

    (sig.exp, defns) match {
      // Case 1: An instance implementation exists. Use it.
      case (_, defn :: Nil) => specializeDef(defn, tpe)
      // Case 2: No instance implementation, but a default implementation exists. Use it.
      case (Some(impl), Nil) => specializeDef(sigToDef(sig.sym, sig.spec, impl), tpe)
      // Case 3: Multiple matching defs. Should have been caught previously.
      case (_, _ :: _ :: _) => throw InternalCompilerException(s"Expected at most one matching definition for '$sym', but found ${defns.size} signatures.", sym.loc)
      // Case 4: No matching defs and no default. Should have been caught previously.
      case (None, Nil) => throw InternalCompilerException(s"No default or matching definition found for '$sym'.", sym.loc)
    }
  }

  /**
    * Converts a signature with an implementation into the equivalent definition.
    */
  private def sigToDef(sigSym: Symbol.SigSym, spec: LoweredAst.Spec, exp: LoweredAst.Expr): LoweredAst.Def = {
    LoweredAst.Def(sigSymToDefnSym(sigSym), spec, exp)
  }

  /**
    * Converts a SigSym into the equivalent DefnSym.
    */
  private def sigSymToDefnSym(sigSym: Symbol.SigSym): Symbol.DefnSym = {
    val ns = sigSym.clazz.namespace :+ sigSym.clazz.name
    new Symbol.DefnSym(None, ns, sigSym.name, sigSym.loc)
  }

  /**
    * Returns the def symbol corresponding to the specialized def `defn` w.r.t. to the type `tpe`.
    */
  private def specializeDef(defn: LoweredAst.Def, tpe: Type)(implicit ctx: Context, root: Root, flix: Flix): Symbol.DefnSym = {
    // Unify the declared and actual type to obtain the substitution map.
    val subst = infallibleUnify(defn.spec.declaredScheme.base, tpe)

    // Check whether the function definition has already been specialized.
    ctx.getDef2Def(defn.sym, tpe) match {
      case None =>
        // Case 1: The function has not been specialized.
        // Generate a fresh specialized definition symbol.
        val freshSym = Symbol.freshDefnSym(defn.sym)

        // Register the fresh symbol (and actual type) in the symbol2symbol map.
        ctx.putDef2Def(defn.sym, tpe, freshSym)

        // Enqueue the fresh symbol with the definition and substitution.
        ctx.enqueue(freshSym, defn, subst)

        // Now simply refer to the freshly generated symbol.
        freshSym
      case Some(specializedSym) =>
        // Case 2: The function has already been specialized.
        // Simply refer to the already existing specialized symbol.
        specializedSym
    }

  }

  /**
    * Specializes the given formal parameters `fparams0` w.r.t. the given substitution `subst0`.
    *
    * Returns the new formal parameters and an environment mapping the variable symbol for each parameter to a fresh symbol.
    */
  private def specializeFormalParams(fparams0: List[FormalParam], subst0: StrictSubstitution)(implicit flix: Flix): (List[FormalParam], Map[Symbol.VarSym, Symbol.VarSym]) = {
    // Return early if there are no formal parameters.
    if (fparams0.isEmpty)
      return (Nil, Map.empty)

    // Specialize each formal parameter and recombine the results.
    val (params, envs) = fparams0.map(p => specializeFormalParam(p, subst0)).unzip
    (params, envs.reduce(_ ++ _))
  }

  /**
    * Specializes the given formal parameter `fparam0` w.r.t. the given substitution `subst0`.
    *
    * Returns the new formal parameter and an environment mapping the variable symbol to a fresh variable symbol.
    */
  private def specializeFormalParam(fparam0: FormalParam, subst0: StrictSubstitution)(implicit flix: Flix): (FormalParam, Map[Symbol.VarSym, Symbol.VarSym]) = {
    val FormalParam(sym, mod, tpe, src, loc) = fparam0
    val freshSym = Symbol.freshVarSym(sym)
    (FormalParam(freshSym, mod, subst0(tpe), src, loc), Map(sym -> freshSym))
  }

  /**
    * Unifies `tpe1` and `tpe2` which must be unifiable.
    */
  private def infallibleUnify(tpe1: Type, tpe2: Type)(implicit root: Root, flix: Flix): StrictSubstitution = {
    Unification.unifyTypes(tpe1, tpe2, RigidityEnv.empty) match {
      case Result.Ok((subst, econstrs)) => // TODO ASSOC-TYPES consider econstrs
        StrictSubstitution(subst, root.eqEnv)
      case Result.Err(_) =>
        throw InternalCompilerException(s"Unable to unify: '$tpe1' and '$tpe2'.", tpe1.loc)
    }
  }

  /**
    * Performs type erasure on the given type `tpe`.
    *
    * Flix does not erase normal types, but it does erase Boolean and caseset formulas.
    */
  private def eraseType(tpe: Type)(implicit root: Root, flix: Flix): Type = tpe match {
    case Type.Var(sym, loc) =>
      sym.kind match {
        case Kind.CaseSet(enumSym) =>
          Type.Cst(TypeConstructor.CaseSet(SortedSet.empty, enumSym), loc)
        case Kind.Eff =>
          // If an effect variable is free, we may assume its Pure due to the subst. lemma.
          Type.Pure
        case _ => tpe
      }

    // Erase concrete effects like Print.
    case Type.Cst(TypeConstructor.Effect(_), _) => Type.EffUniv

    case Type.Cst(_, _) => tpe

    case Type.Apply(tpe1, tpe2, loc) =>
      val t1 = eraseType(tpe1)
      val t2 = eraseType(tpe2)
      Type.Apply(t1, t2, loc)

    case Type.Alias(sym, args, tpe, loc) =>
      val as = args.map(eraseType)
      val t = eraseType(tpe)
      Type.Alias(sym, as, t, loc)

    case Type.AssocType(cst, arg, _, _) =>
      val a = eraseType(arg)
      EqualityEnvironment.reduceAssocTypeStep(cst, a, root.eqEnv).get
  }

}
