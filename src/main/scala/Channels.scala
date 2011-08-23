// Message passing constructions: synchronous channels and swap
// channels.

package chemistry

import scala.annotation.tailrec
import java.util.concurrent.locks._

private abstract class Message[A,B] extends DeletionFlag {
  def exchange: Reagent[B,A]
}

/*
final private case class CMessage[A,B](
  m: A, k: Reagent[B,Unit]
) extends Message[A,B] {
  def isDeleted = false
  val reagent = k compose ret(m)
}
*/

final private case class RMessage[A,B](
  m: A, waiter: Waiter[B]
) extends Message[A,B] {
/*
final private case class RMessage[A,B,C](
  m: A, k: Reagent[B,C], waiter: Waiter[C]
) extends Message[A,B] {
*/

  private case object CompleteExchange extends Reagent[B,A] {
    def tryReact(b: B, rx: Reaction, offer: Offer[A]): A = waiter.consume !? () match {
      case None => throw ShouldBlock
//      case Some(()) => kk.tryReact(m, rx, offer)
      case Some(()) => {
	waiter.setAnswer(b)
	m
      }
    }
    def compose[C](next: Reagent[A,C]) = throw Util.Impossible

/*
  private case class CompleteExchange[D](kk: Reagent[A,D]) 
	       extends Reagent[C,D] {
    def tryReact(c: C, rx: Reaction, offer: Offer[D]): D = waiter.consume !? () match {
      case None => throw ShouldBlock
      case Some(()) => kk.tryReact(m, rx, offer)
    }

      kk.tryReact(m, 
		  rx.withPostCommit((_:Unit) => {
		    waiter.setAnswer(c)
		    waiter.wake
		  }), 
		  offer)

    def compose[E](next: Reagent[D,E]): Reagent[C,E] =
      CompleteExchange(kk >=> next)
*/
  }

  val exchange: Reagent[B, A] = //k >=> CompleteExchange(Commit[A]())
    CompleteExchange
  def isDeleted = waiter.isActive
}

private final case class Endpoint[A,B,C]( 
  outgoing: Pool[Message[A,B]],
  incoming: Pool[Message[B,A]],
  k: Reagent[B,C]
) extends Reagent[A,C] {
  def tryReact(a: A, rx: Reaction, offer: Offer[C]): C = {
    // sadly, @tailrec not acceptable here due to exception handling
    var cursor = incoming.cursor
    var retry: Boolean = false
    while (true) cursor.get match {
      case null if retry => throw ShouldRetry
      case null          => {
	offer match {
//	  case (w: Waiter[_]) => outgoing.put ! RMessage(a, k, w)
	  case (w: Waiter[_]) => outgoing.put ! RMessage(a, w.asInstanceOf[Waiter[B]])
	  case null => {} // do nothing
	}
	throw ShouldRetry
      }
      case incoming.Node(msg, next) => try {
	return msg.exchange.tryReact(a, rx, null).asInstanceOf[C]
	//return msg.exchange.compose(k).tryReact(a, rx, offer)
      } catch {
	case ShouldRetry => retry = true; cursor = next
	case ShouldBlock => cursor = next
      }	      
    }
    throw Util.Impossible
  }
  def compose[D](next: Reagent[C,D]) = 
    Endpoint(outgoing,incoming,k.compose(next))
}
object SwapChan {
  @inline def apply[A,B](): (Reagent[A,B], Reagent[B,A]) = {
    val p1 = new Pool[Message[A,B]]
    val p2 = new Pool[Message[B,A]]
    (Endpoint(p1,p2,Commit[B]()), Endpoint(p2,p1,Commit[A]()))
  }
}
object Chan {
  @inline def apply[A](): (Reagent[Unit,A], Reagent[A,Unit]) =
    SwapChan[Unit,A]()
}
