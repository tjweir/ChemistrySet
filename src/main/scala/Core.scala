// The reagent implementation

package chemistry

import java.util.concurrent.atomic._
import java.util.concurrent.locks._
import scala.annotation.tailrec

private object Util {
  def undef[A]: A = throw new Exception()
}

sealed private abstract class LogEntry
private case class CASLog[A](r: AtomicReference[A], ov: A, nv: A) 
	     extends LogEntry

sealed private class Transaction {}

private abstract class WaiterStatus
private case object Catalyst extends WaiterStatus
private case object Waiting  extends WaiterStatus
private case object Finished extends WaiterStatus

sealed private abstract class AbsWaiter
sealed private case class Waiter[A,B](
  r: Reagent[A,B], 
  arg: A,
  var answer: AnyRef,
  status: Ref[WaiterStatus], 
  thread: Thread
) extends AbsWaiter

private case object Impossible extends Exception

private abstract class BacktrackCommand extends Exception
private case object ShouldBlock extends BacktrackCommand
private case object ShouldRetry extends BacktrackCommand

private sealed abstract class ReagentK[-A,+B] {
  private[chemistry] def tryReact(a: A, trans: Transaction): B
}

private sealed case class BindK[A,B,C](k1: A => Reagent[Unit, B], 
				       k2: ReagentK[B,C]) 
		    extends ReagentK[A,C] {
  def tryReact(a: A, trans: Transaction): C = k1(a).tryReact((), trans, k2)
}

private object FinalK extends ReagentK[Any,Any] {
  // ID continuation at the moment; eventually will be responsible for kCAS
  def tryReact(a: Any, trans: Transaction): Any = a
}

sealed abstract class Reagent[-A, +B] {
  private[chemistry] 
  def tryReact[C](a: A, trans: Transaction, k: ReagentK[B,C]): C

  final def !(a: A): B = {
    // we want only one global instance of FinalK, but the cost is a
    // silly typecast
    val finalk = FinalK.asInstanceOf[ReagentK[B,B]] 
    
    def slowPath: B = {
      val status = Ref[WaiterStatus](Waiting)
      // val recheck: Reagent[A] = for {
      // 	_ <- status.cas(Waiting, Finished)
      // 	r <- this
      // } yield r
      // val waiter = Waiter(this, null, status, Thread.currentThread())

      //logWait(waiter)

      // written with while because scalac couldn't handle tail recursion
      while (true) status.get() match {
//	case Finished => return waiter.answer.asInstanceOf[A]
	case _ => try {
//	  return recheck.tryReact(a, null, finalk) 
	  return tryReact(a, null, finalk) 
	} catch {
	  case ShouldRetry => () // should backoff
//	  case ShouldBlock => LockSupport.park(waiter)
	}
      }
      throw Impossible
    }

    // first try "fast path": react without creating/enqueuing a waiter
    // written with while because scalac couldn't handle tail recursion
    while (true) {
      try {
    	return tryReact(a, null, finalk) 
      } catch {
    	case ShouldRetry => () // should backoff
        case ShouldBlock => return slowPath
      }
    }
    throw Impossible
  }

  @inline final def !?(a:A) : Option[B] = {
    // we want only one global instance of FinalK, but the cost is a
    // silly typecast
    val finalk = FinalK.asInstanceOf[ReagentK[B,B]] 

    try {
      Some(tryReact(a, null, finalk))
    } catch {
      case ShouldRetry => None	// should we actually retry here?  if
				// we do, more informative: a failed
				// attempt entails a linearization
				// where no match was possible.  but
				// could diverge...
      case ShouldBlock => None
    }
  }

  @inline final def flatMap[C](k: B => Reagent[Unit,C]): Reagent[A,C] = 
    Bind(this, k)
  @inline final def map[C](f: B => C): Reagent[A,C] = 
    Bind(this, (x: B) => ret(f(x)))
  @inline final def >>[C](k: Reagent[Unit,C]): Reagent[A,C] = 
    Bind(this, (_:B) => k)

  // @inline final def <+>[C <: A, D >: B](
  //   that: Reagent[C,D]): Reagent[C,D] = 
  //   new Reagent(this.choices ++ that.choices)

}

private case class Bind[A,B,C](c: Reagent[A,B], k1: B => Reagent[Unit,C]) 
	     extends Reagent[A,C] {
  final def tryReact[D](a: A, trans: Transaction, k2: ReagentK[C,D]): D = 
    c.tryReact(a, trans, BindK(k1, k2))
}

sealed case class ret[A](pure: A) extends Reagent[Unit,A] {
  final def tryReact[B](u: Unit, trans: Transaction, k: ReagentK[A,B]): B = 
    k.tryReact(pure, trans)
}

object retry extends Reagent[Any,Nothing] {
  final def tryReact[A](a: Any, trans: Transaction, k: ReagentK[Nothing,A]): A = 
    throw ShouldRetry
}

// this really needs a better name
// could call it "reagent"
sealed case class loop[A,B](c: A => Reagent[Unit,B]) extends Reagent[A,B] {
  final def tryReact[C](a: A, trans: Transaction, k: ReagentK[B,C]): C = 
    c(a).tryReact((), trans, k)
}

/*
private class Endpoint[A,B] extends Reagent[A,B] {
  var dual: Endpoint[B,A] = null
}
object SwapChan {
  def apply[A,B]: (Reagent[A,B], Reagent[B,A]) = {
    val c1 = new Endpoint[A,B]
    val c2 = new Endpoint[B,A]
    c1.dual = c2; c2.dual = c1
    (c1, c2)
  }
}
*/

class Ref[A](init: A) extends AtomicReference[A](init) {
//  private final var data = new AtomicReference[A](init)

//  private val waiters = new MSQueue[]()

  case object read extends Reagent[Unit, A] {
    final def tryReact[B](u: Unit, trans: Transaction, k: ReagentK[A,B]): B = 
      k.tryReact(get(), trans)
  }

  private sealed class CAS(expect: A, update: A) extends Reagent[Unit, Unit] {
    final def tryReact[B](u: Unit, trans: Transaction, k: ReagentK[Unit,B]): B ={
      compareAndSet(expect, update)
      k.tryReact((), trans)
    }
  }
  @inline final def cas(ov:A,nv:A): Reagent[Unit,Unit] = new CAS(ov,nv) 

  private sealed class Upd[B,C](f: (A,B) => (A,C)) extends Reagent[B, C] {
    final def tryReact[D](b: B, trans: Transaction, k: ReagentK[C,D]): D = {
      val ov = get()
      val (nv, ret) = f(ov, b)
      compareAndSet(ov, nv)
      k.tryReact(ret, trans)
    }
  }
  private sealed class UpdUnit[B](f: A => (A,B)) extends Reagent[Unit, B] {
    final def tryReact[C](u: Unit, trans: Transaction, k: ReagentK[B,C]): C = {
      val ov = get()
      val (nv, ret) = f(ov)
      compareAndSet(ov, nv)
      k.tryReact(ret, trans)
    }
  }

  @inline final def upd[B,C](f: (A,B) => (A,C)): Reagent[B, C] = 
    new Upd(f)
  @inline final def upd[B](f: A => (A,B)): Reagent[Unit, B] = 
    new UpdUnit(f)
}
object Ref {
  final def apply[A](init: A): Ref[A] = new Ref(init)
  final def unapply[A](r: Ref[A]): Option[A] = Some(r.get()) 
}
object upd {
  @inline final def apply[A,B,C](r: Ref[A])(f: (A,B) => (A,C)): Reagent[B,C] = 
    r.upd(f)
  @inline final def apply[A,B](r: Ref[A])(f: A => (A,B)): Reagent[Unit,B] = 
    r.upd(f)
}

