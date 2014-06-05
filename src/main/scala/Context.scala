package provingGround

import provingGround.HoTT._
import scala.reflect.runtime.universe.{Try => UnivTry, Function => FunctionUniv, _}
import annotation._

object Context{
  trait DefnEquality{
    val lhs : Term
    val rhs: Term
    val freevars: List[Term]
    
    def map(f: Term => Term): DefnEquality
    
  }
  
  case class DefnEqual(lhs: Term, rhs: Term, freevars : List[Term] = List()) extends DefnEquality{
    def map(f : Term => Term) = DefnEqual(f(lhs), f(rhs), freevars map (f))
    
    def apply(substitutions : Term => Option[Term]) = DefnEqual.instantiate(substitutions)(this)
    
    def apply(substitutions : PartialFunction[Term, Term]) = DefnEqual.instantiate(substitutions.lift)(this)
  }
  
  object DefnEqual{
    def instantiate(substitutions : Term => Option[Term])(dfn : DefnEqual) : Option[DefnEquality] = {
      if (dfn.freevars.isEmpty) Some(dfn) 
      else for (headvar <- dfn.freevars.headOption; 
      value <- substitutions(headvar); 
      recval <- instantiate(substitutions)(DefnEqual(dfn.lhs.subs(headvar, value), dfn.rhs.subs(headvar, value), dfn.freevars.tail))) 
        yield recval
    }
  }
  
  case class Defn[+A](lhsname : A, typ : Typ[Term], rhs: Term, freevars: List[Term]) extends DefnEquality{    
    val lhs = typ.symbObj(lhsname)
    
    def map(f : Term => Term, F : Typ[Term]=> Typ[Term]) = Defn(lhsname, F(typ), f(rhs), freevars map (f))
    
    def map(f: Term => Term) = Defn(lhsname, f(rhs))
  }
  
  object Defn{
    def infer[A](name: A, rhs : Term) : Defn[A] = Defn(name, rhs.typ, rhs, List())
    
    def apply[A](name: A, rhs : Term) = infer(name, rhs)
  }
  
  trait Context[+A, +U <: Term , V <: Term] extends TypSeq[U, V]{
//    val typ: Typ[PtnType]
    
    type PtnType <: U
    
    def foldin : Typ[V] => PtnType => V
    
    def foldinSym[B](tp: Typ[V])(name: B) = foldin(tp)(apply(tp).symbObj(name))
    
    def symblist[A](tp: Typ[V])(varnames: List[A]): List[Term]
    
    def constants : Seq[Term]
    
    def defns : Seq[Defn[A]]
    
    def defnEqualities : Seq[DefnEquality]
    
    def eliminate(isVar : Term => Boolean): Term => Term
    
    val elim : Term => Term
    
    type ArgType
    
    val instantiate : ArgType => Term => Term
    
    def lmbda(x : Term) = LambdaMixin(x, this)
    
    def deplmbda(x : Term) = LambdaMixin(x, this, true)
    
    def cnst(x: Term) = KappaMixin(x, this)
    
    def dfn(x: Term, y: Term, local: Boolean = true) = DefnMixin(Defn(x, y), this)
    
    def dfneql(lhs: Term, rhs: Term, simp: Boolean = false) = DefnEqualityMixin(DefnEqual(lhs, rhs), this)
    
    def globaldfn(x: Term, y: Term, local: Boolean = true) = GlobalDefnMixin(Defn(x, y), this)
    
    def simpeql(lhs: Term, rhs: Term, simp: Boolean = false) = SimpEqualityMixin(DefnEqual(lhs, rhs), this)
  }
  
  object Context{
    case class empty[U <: Term : TypeTag]() extends Context[Any, U, U]{
    	type PtnType = U

    	def apply(tp : Typ[U]) : Typ[PtnType] = tp
    	
    	def foldin : Typ[U] => PtnType => U = _ => (t) => t
    	
    	def symblist[A](tp: Typ[U])(varnames: List[A]) = List()
    	
    	def constants : Seq[Term] = List.empty
    
    	def defns : Seq[Defn[U]] = List.empty
    
    	def defnEqualities : Seq[DefnEquality] = List.empty
    
    	def eliminate(isVar : Term => Boolean): Term => Term = (x) => x
    	
    	val elim = (t: Term) => t
    	
    	type ArgType = Unit
    	
    	val instantiate : ArgType => Term => Term = _ => (t) => t
      
    }
    
    def apply[U <: Term : TypeTag] = empty[U]
    
    
  }
  

  
  
  case class LambdaMixin[+A, +U<: Term, V <: Term](variable: Term, tail: Context[A, U, V], dep: Boolean = false) extends Context[A, FuncTerm[Term,U], V]{
    type PtnType = FuncTerm[Term, tail.PtnType]
    
    def foldin : Typ[V] => PtnType => V = (tp) => (f) => tail.foldin(tp)(f(variable))
    
    def symblist[A](tp: Typ[V])(varnames: List[A]) = apply(tp).symbObj(varnames.head) :: tail.symblist(tp)(varnames.tail)
    
    def constants = variable +: tail.constants
    
    def eliminator(y : Term) = Lambda(variable, y)
    
    def defns : Seq[Defn[A]] = tail.defns map ((dfn) => dfn.map(optlambda(variable)))
    
    def defnEqualities : Seq[DefnEquality] = tail.defnEqualities map ((dfn) => dfn.map(optlambda(variable)))
    
    def eliminate(isVar : Term => Boolean): Term => Term = (y) => 
      if (isVar(variable)) Lambda(x, tail.eliminate(isVar)(y))
      else tail.eliminate(isVar)(y)
    
      
    val elim = (y: Term) => Lambda(variable, tail.elim(y))
    
    type ArgType = (Term, tail.ArgType)
    
    val instantiate : ArgType => Term => Term = (fstrest) => (t) => {
      val rest: tail.ArgType = fstrest._2
      val fst = fstrest._1
      val func = Lambda(variable,tail.instantiate(rest)(t))
      func(fst)
    }
    
    // If tail.typ is a function of variable, we get a Pi-type instead.
    /*
    val typ = if (dep) {
      val fibre = (t : Term) => tail.typ subs (x, t)
	    
	    val family = typFamilyDefn[Term, tail.PtnType](variable.typ, MiniVerse(tail.typ), fibre)
	    PiTyp(family)
    }
    else FuncTyp(variable.typ, tail.typ)
    */
    
    def apply(tp : Typ[V]) : Typ[PtnType] = if (dep) {
      val fibre = (t : Term) => tail(tp) subs (x, t)
	    
	    val family = typFamilyDefn[Term, tail.PtnType](variable.typ, MiniVerse(tail(tp)), fibre)
	    PiTyp(family)
    }
    else FuncTyp(variable.typ, tail(tp))
    
    
  }
  
  case class KappaMixin[+A, +U<: Term, V <: Term](const : Term, tail: Context[A, U, V]) extends Context[A, U, V]{
    type PtnType = tail.PtnType
    
    def foldin : Typ[V] => PtnType => V = tail.foldin
    
    def symblist[A](tp: Typ[V])(varnames: List[A]) = tail.symblist(tp)(varnames)
    
    def constants = const +: tail.constants
    
    def defns : Seq[Defn[A]] = tail.defns
    
    def defnEqualities : Seq[DefnEquality] = tail.defnEqualities
    
    def eliminate(isVar : Term => Boolean): Term => Term = tail.eliminate(isVar)
    
    val elim = tail.elim
    
    type ArgType = tail.ArgType
    
    val instantiate : ArgType => Term => Term = _ => (t) => t
    
//    val typ = tail.typ
    
    def apply(tp : Typ[V]) : Typ[PtnType] =tail(tp)
  }
  
  case class DefnMixin[+A, +U<: Term, V <: Term](dfn : Defn[A], tail: Context[A, U, V], dep: Boolean = false) extends Context[A, FuncTerm[Term,U], V]{
    type PtnType = FuncTerm[Term, tail.PtnType]
    
    def foldin : Typ[V] => PtnType => V = (tp) => (f) => tail.foldin(tp)(f(dfn.lhs))
    
    def symblist[A](tp : Typ[V])(varnames: List[A]) = apply(tp).symbObj(varnames.head) :: tail.symblist(tp)(varnames.tail)
    
    def constants : Seq[Term] = tail.constants
    
    def defns : Seq[Defn[A]] = dfn +: tail.defns
    
    def defnEqualities : Seq[DefnEquality] = dfn +: tail.defnEqualities
    
    def eliminate(isVar : Term => Boolean): Term => Term = tail.eliminate(isVar)
    
    val elim  =  tail.elim andThen ((t : Term) => t.subs(dfn.lhs, dfn.rhs))
    
    type ArgType = (Term, tail.ArgType)
    
    val instantiate : ArgType => Term => Term = (fstrest) => (t) => {
      val rest: tail.ArgType = fstrest._2
      val fst = fstrest._1
      (tail.instantiate(rest)(t)).subs(dfn.lhs, dfn.rhs)
    }
    								
    /*
    val typ = if (dep) {
      val fibre = (t : Term) => tail.typ subs (x, t)
	    
	    val family = typFamilyDefn[Term, tail.PtnType](dfn.lhs.typ, MiniVerse(tail.typ), fibre)
	    PiTyp[Term, tail.PtnType](family)
    }
    else FuncTyp(dfn.lhs.typ, tail.typ)
    */
    
    def apply(tp : Typ[V]) : Typ[PtnType] = if (dep) {
      val fibre = (t : Term) => tail(tp) subs (x, t)
	    
	    val family = typFamilyDefn[Term, tail.PtnType](dfn.lhs.typ, MiniVerse(tail(tp)), fibre)
	    PiTyp[Term, tail.PtnType](family)
    }
    else FuncTyp(dfn.lhs.typ, tail(tp))
  } 
  
  case class GlobalDefnMixin[+A, +U <: Term, V <: Term](dfn : Defn[A], tail: Context[A, U, V]) extends Context[A, U, V]{
    type PtnType = tail.PtnType
    
    def foldin : Typ[V] => PtnType => V = tail.foldin
    
    def symblist[A](tp : Typ[V])(varnames: List[A]) = tail.symblist(tp)(varnames)
    
    def constants : Seq[Term] = tail.constants
    
    def defns : Seq[Defn[A]] = dfn +: tail.defns
    
    def defnEqualities : Seq[DefnEquality] = dfn +: tail.defnEqualities
    
    def eliminate(isVar : Term => Boolean): Term => Term = tail.eliminate(isVar)
    
    val elim  = tail.elim
    
    type ArgType = tail.ArgType
    
    val instantiate : ArgType => Term => Term = _ => (t) => t
    
//    val typ = tail.typ		
    
    def apply(tp : Typ[V]) : Typ[PtnType] = tail(tp)
  }
  
  case class DefnEqualityMixin[+A, +U <: Term, V <: Term](eqlty : DefnEquality, tail: Context[A, U, V]) extends Context[A, U, V]{
    type PtnType = tail.PtnType
    
    def apply(tp : Typ[V]) : Typ[PtnType] = tail(tp)
    
    def foldin : Typ[V] => PtnType => V = tail.foldin
    
    def symblist[A](tp: Typ[V])(varnames: List[A]) = tail.symblist(tp)(varnames)
    
    def constants : Seq[Term] = tail.constants
    
    def defns : Seq[Defn[A]] = tail.defns
    
    def defnEqualities : Seq[DefnEquality] = eqlty +: tail.defnEqualities
    
    def eliminate(isVar : Term => Boolean): Term => Term = tail.eliminate(isVar)
    
    val elim  = tail.elim
    
    type ArgType = tail.ArgType
    
    val instantiate : ArgType => Term => Term = _ => (t) => t
//    val typ = tail.typ								
  }
  
  case class SimpEqualityMixin[+A, +U <: Term, V <: Term](eqlty : DefnEquality, tail: Context[A, U, V], dep : Boolean = false) extends Context[A, FuncTerm[Term,U], V]{
    type PtnType = FuncTerm[Term, tail.PtnType]
    
    def foldin : Typ[V] => PtnType => V = (tp) => (f) => tail.foldin(tp)(f(eqlty.lhs))
    
    def symblist[A](tp : Typ[V])(varnames: List[A]) = apply(tp).symbObj(varnames.head) :: tail.symblist(tp)(varnames.tail)
    
    def constants : Seq[Term] = tail.constants
    
    def defns : Seq[Defn[A]] = tail.defns
    
    def defnEqualities : Seq[DefnEquality] = eqlty +: tail.defnEqualities
    
    def eliminate(isVar : Term => Boolean): Term => Term = tail.eliminate(isVar)
    
    val elim  =  tail.elim andThen ((t : Term) => t.subs(eqlty.lhs, eqlty.rhs))
    
    type ArgType = (Term, tail.ArgType)
    
    val instantiate : ArgType => Term => Term = (fstrest) => (t) => {
      val rest: tail.ArgType = fstrest._2
      val fst = fstrest._1
      (tail.instantiate(rest)(t)).subs(eqlty.lhs, eqlty.rhs)
    }
    
    /*
    val typ = if (dep) {
      val fibre = (t : Term) => tail.typ subs (x, t)
	    
	    val family = typFamilyDefn[Term, tail.PtnType](eqlty.lhs.typ, MiniVerse(tail.typ), fibre)
	    PiTyp(family)
    }
    else FuncTyp(eqlty.lhs.typ, tail.typ)
    */
    
    def apply(tp : Typ[V]) : Typ[PtnType] = if (dep) {
      val fibre = (t : Term) => tail(tp) subs (x, t)
	    
	    val family = typFamilyDefn[Term, tail.PtnType](eqlty.lhs.typ, MiniVerse(tail(tp)), fibre)
	    PiTyp(family)
    }
    else FuncTyp(eqlty.lhs.typ, tail(tp))
  }
  
  

    /*
   * Change in context for a TypPatn (i.e., simple pattern ending in W).
   * Should allow for dependent types when making a lambda.
   */
  def recContextChange[A, V<: Term](f : => (FuncTerm[Term, Term]), 
        W : Typ[V], X : Typ[V]) : (A, TypPtnLike, Context[A, Term, V]) => Context[A, Term, V] = (varname, ptn, ctx) => {
    val x = ptn(W).symbObj(varname)
    ctx lmbda(x) lmbda(ptn.induced(W, X)(f)(x))
  }
  
  def simpleContextChange[A, V<: Term](W : Typ[V]) : (A, TypPtnLike, Context[A, Term, V]) => Context[A, Term, V] = (varname, ptn, ctx) => {
    val x = ptn(W).symbObj(varname)
    ctx lmbda(x)
  }
  
    /*
   * Change in context  for Induction for a TypPattern - check
   */
  def indContextChange[A, V<: Term](f : => (FuncTerm[Term, Term]), 
      W : Typ[V], Xs : Term => Typ[V]) : (A, TypPtnLike, Context[A, Term, V]) => Context[A, Term, V] = (varname, ptn, ctx) =>   {
    val x =  ptn(W).symbObj(varname)
    ctx lmbda(x) lmbda(ptn.inducedDep(W, Xs)(f)(x))
  }
      
  private type Change[A, V <: Term] = (A, TypPtnLike, Context[A, Term, V]) => Context[A, Term, V]
  
  /*
   * The context, recursively defined, for the constructor of a single context.
   * This is also a change, to be applied by default to the empty context of the target type.
   */
  def cnstrContext[A, V<: Term](
      ptn : PolyPtn[Term], varnames : List[A], 
      W : Typ[V], 
      change: Change[A, V])(ctx: Context[A, Term, V] = Context.empty[Term]) : Context[A, Term, V] = {
    ptn match {
      case tp: TypPtnLike => change(varnames.head, tp, ctx)
      case FuncPtn(tail, head) => cnstrContext(head, varnames.tail, W, change)(change(varnames.head, tail, ctx))
      case CnstFncPtn(tail : Typ[_], head) => cnstrContext(head, varnames.tail, W, change)( ctx lmbda(tail.symbObj(varnames.head)))
      case DepFuncPtn(tail, headfibre , _) =>
        val x  = tail(W).symbObj(varnames.head).asInstanceOf[Term]
        cnstrContext(headfibre(x), varnames.tail, W, change)( ctx lmbda(x))
      case CnstDepFuncPtn(tail, headfibre , _) =>
        val x = tail.symbObj(varnames.head)
        cnstrContext(headfibre(x), varnames.tail, W, change)( ctx lmbda(x))
    }
  }
  
  def cnstrRecContext[A, V<: Term](f : => (FuncTerm[Term, Term]), 
      ptn : PolyPtn[Term], varnames : List[A], 
      W : Typ[V], 
      X : Typ[V])(ctx: Context[A, Term, V] = Context.empty[Term]) : Context[A, Term, V] = {
    val change = recContextChange[A, V](f, W, X)
    cnstrContext(ptn, varnames, W, change)(ctx)
  }
  
  def cnstrIndContext[A, V<: Term, U <: Term](f : => (FuncTerm[Term, Term]), 
      ptn : PolyPtn[U], varnames : List[A], 
      W : Typ[V], 
      Xs :  Term => Typ[V])(ctx: Context[A, Term, V]) : Context[A, Term, V] = {
    val change = indContextChange[A, V](f, W, Xs)
    cnstrContext(ptn, varnames, W, change)(ctx)
  }
  
  
  def cnstrSimpleContext[A, V<: Term, U <: Term]( 
      ptn : PolyPtn[U], varnames : List[A], 
      W : Typ[V])(ctx: Context[A, Term, V] = Context.empty[Term]) : Context[A, Term, V] = {
		  val change = simpleContextChange[A, V](W)
    cnstrContext(ptn, varnames, W, change)(ctx)
  }
  
  
  case class RecInduced(cons : Term, func: Term => Term)
  
  case class IndInduced(cons: Term, func: Term => Term)
  
  def addConstructor[V <: Term](f : => (FuncTerm[Term, Term]), 
      cnstr : Constructor, varnames : List[Any], 
      W : Typ[Term], 
      X : Typ[Term]) : Context[Any, Term, V] => Context[Any, Term, V] = ctx => {
        val cnstrctx = cnstrRecContext(f, cnstr.pattern, varnames, W, X)()
        val name = cnstrctx.foldinSym(FuncTyp(W, X))(RecInduced(cnstr.cons, f))
      		ctx lmbda (name)
      }
      
  def addIndConstructor[V <: Term](f : => (FuncTerm[Term, Term]), 
      cnstr : Constructor, varnames : List[Any], 
      W : Typ[Term], 
      Xs : Term => Typ[Term]) : (Context[Any, Term, V]) => (Context[Any, Term, V]) =  ctx => {
        val varctx = cnstrSimpleContext(cnstr.pattern, varnames, W)()
        val variable = varctx.foldin(W)(cnstr.cons.asInstanceOf[varctx.PtnType])
        val target = Context.empty[Term]
        val cnstrctx = cnstrIndContext(f, cnstr.pattern, varnames, W, Xs)(target)
        val name = cnstrctx.foldinSym(Xs(variable))(IndInduced(cnstr.cons, f))
      		 ctx lmbda (name)
      }
      
  def recContext(f : => (FuncTerm[Term, Term]), 
      cnstrvars : List[(Constructor, List[Any])], 
      W : Typ[Term], 
      X : Typ[Term]) ={
    val add : (Context[Any, Term, FuncTerm[Term, Term]], 
        (Constructor, List[Any])) => Context[Any, Term, FuncTerm[Term, Term]] = (ctx, cnvr) =>
      addConstructor(f, cnvr._1, cnvr._2, W, X)(ctx)
      val empty : Context[Any, Term, FuncTerm[Term, Term]] = Context.empty[FuncTerm[Term, Term]]
    (empty /: cnstrvars)(add)
  }
  
  def indContext(f : => (FuncTerm[Term, Term]), 
      cnstrvars : List[(Constructor, List[Any])], 
      W : Typ[Term], 
      Xs : Term => Typ[Term], univ : Typ[Typ[Term]]) ={
    val add : (Context[Any, Term, FuncTerm[Term, Term]], 
        (Constructor, List[Any])) => Context[Any, Term, FuncTerm[Term, Term]] = (ctx, cnvr) =>
      addIndConstructor(f, cnvr._1, cnvr._2, W, Xs)(ctx)
      
      val empty : Context[Any, Term, FuncTerm[Term, Term]] = Context.empty[FuncTerm[Term, Term]]
    (empty /: cnstrvars)(add)
  }
  
  
  def recFunction(f : => (FuncTerm[Term, Term]), 
      cnstrvars : List[(Constructor, List[Any])], 
      W : Typ[Term], 
      X : Typ[Term]) = {

    recContext(f, cnstrvars, W, X).foldinSym(FuncTyp(W, X))(RecSymbol(W, X))}
  
  def indFunction(f : => (FuncTerm[Term, Term]), 
      cnstrvars : List[(Constructor, List[Any])], 
      W : Typ[Term], 
      Xs : Term => Typ[Term], univ : Typ[Typ[Term]]) = {
    val family = typFamilyDefn(W, univ, Xs)
    indContext(f, cnstrvars, W, Xs, univ).foldinSym(PiTyp(family))(IndSymbol(W, Xs))
  }
  
  // Should avoid type coercion by having proper types for constructors and polypatterns.
  def recIdentities(f : => (FuncTerm[Term, Term]), 
      cnstrvars : List[(Constructor, List[Any])], 
      W : Typ[Term], 
      X : Typ[Term]) = {

    val recfn = recContext(f, cnstrvars, W, X).foldinSym(FuncTyp(W, X))(RecSymbol(W, X))
    
    def eqn(cnstr : Constructor, varnames : List[Any]) = {	
    	val ptn = cnstr.pattern
    	val argctx = cnstrSimpleContext(ptn, varnames, W)()
    	val cons = cnstr.cons.asInstanceOf[argctx.PtnType]
    	val arg = argctx.foldin(W)(cons)
    	val lhs = recfn(arg)
    	val rhsctx = cnstrRecContext(f, ptn, varnames, W, X)()
    	val rhs = rhsctx.foldinSym(FuncTyp(W, X))(RecInduced(cons, f))
    	DefnEqual(lhs, rhs, argctx.symblist(W)(varnames))
    }
    
    val eqntail = for ((cnstr, varnames) <- cnstrvars) yield eqn(cnstr, varnames)
	
    val allvars = eqntail flatMap (_.freevars)
    
    DefnEqual(f, recfn, allvars) :: eqntail
  }
  
  def indIdentities(f : => (FuncTerm[Term, Term]), 
      cnstrvars : List[(Constructor, List[Any])], 
      W : Typ[Term], 
      Xs : Term => Typ[Term], univ : Typ[Typ[Term]]) = {
    
    val indfn = indFunction(f, cnstrvars, W, Xs, univ)
    
     def eqn(cnstr : Constructor, varnames : List[Any]) = {	
    	val ptn = cnstr.pattern
    	val argctx = cnstrSimpleContext(ptn, varnames, W)()
    	val cons = cnstr.cons.asInstanceOf[argctx.PtnType]
    	val arg = argctx.foldin(W)(cons)
    	val lhs = indfn(arg)
    	val varctx = cnstrSimpleContext(cnstr.pattern, varnames, W)()
        val variable = varctx.foldin(W)(cnstr.cons.asInstanceOf[varctx.PtnType])
        val target = Context.empty[Term]
    	val rhsctx = cnstrIndContext(f, ptn, varnames, W, Xs)(target)
    	val rhs = rhsctx.foldinSym(Xs(variable))(RecInduced(cons, f))
    	DefnEqual(lhs, rhs, argctx.symblist(W)(varnames))
    }
    
    val eqntail = for ((cnstr, varnames) <- cnstrvars) yield eqn(cnstr, varnames)
	
    val allvars = eqntail flatMap (_.freevars)
    
    DefnEqual(f, indfn, allvars) :: eqntail
    
  }
  
  /*
   * The symbolic object for defining a recursion function from a context.
   */
  case class RecSymbol(W : Typ[Term], X : Typ[Term])
  
  case class IndSymbol(W : Typ[Term], Xs : Term => Typ[Term])
  
  
  trait PolyPtnInstance[D]{
    
    def apply(tp: Typ[Term]) : Typ[Term]
  }
  
  case class TypPtnInstance[D](ptn: TypPtn[Term], arg: D) extends PolyPtnInstance[D]{
    def apply(tp: Typ[Term]) : Typ[Term] = ptn(tp)
  }
  
  case class FuncTypPtnInstance[D](head: TypPtnInstance[D], tail: TypPtnInstance[D]) extends PolyPtnInstance[D]{
    def apply(tp: Typ[Term]) = FuncTyp(head(tp), tail(tp))
   
  }
  
  case class CnstFuncTypPtnInstance[D](tail: Typ[Term], headfibre: Term => PolyPtnInstance[D]) extends PolyPtnInstance[D]{
    def apply(W : Typ[Term]) = {
	    val fiber = typFamilyDefn[Term, Term](tail, Universe(0),  (t : Term) => headfibre(t)(W))
	    PiTyp[Term, Term](fiber)
	  }
  }
}

