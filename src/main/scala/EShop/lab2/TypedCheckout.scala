package EShop.lab2

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.{Behaviors, ActorContext}
import akka.actor.typed.{ActorRef, Behavior}
import scala.language.postfixOps

import scala.concurrent.duration._
import EShop.lab3.OrderManager
import EShop.lab3.Payment

object TypedCheckout {

  sealed trait Data
  case object Uninitialized                               extends Data
  case class SelectingDeliveryStarted(timer: Cancellable) extends Data
  case class ProcessingPaymentStarted(timer: Cancellable) extends Data

  sealed trait Command
  case object StartCheckout                                                                  extends Command
  case class SelectDeliveryMethod(method: String)                                            extends Command
  case object CancelCheckout                                                                 extends Command
  case object ExpireCheckout                                                                 extends Command
  case class SelectPayment(payment: String, orderManagerRef: ActorRef[OrderManager.Command]) extends Command
  case object ExpirePayment                                                                  extends Command
  case object ConfirmPaymentReceived                                                         extends Command

  sealed trait Event
  case object CheckOutClosed                           extends Event
  case class PaymentStarted(paymentRef: ActorRef[Any]) extends Event
}

class TypedCheckout(
  cartActor: ActorRef[TypedCartActor.Command]
) {
  import TypedCheckout._

  val checkoutTimerDuration: FiniteDuration = 1 seconds
  val paymentTimerDuration: FiniteDuration  = 1 seconds

  private def scheduleCheckoutTimer(context: ActorContext[TypedCheckout.Command]): Cancellable = 
    context.scheduleOnce(delay = checkoutTimerDuration, target = context.self, msg =  ExpireCheckout)
  
  private def schedulePaymentTimer(context: ActorContext[TypedCheckout.Command]): Cancellable = 
    context.scheduleOnce(delay = paymentTimerDuration, target = context.self, msg =  ExpirePayment)


  def start: Behavior[TypedCheckout.Command] = Behaviors.receive(
    (context, msg) =>
      msg match {
        case StartCheckout =>
          selectingDelivery(timer = scheduleCheckoutTimer(context))
        case _ =>
          Behaviors.same
      }

  )

  def selectingDelivery(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receive(
    (context, msg) =>
      msg match {
        case SelectDeliveryMethod(method) =>
          selectingPaymentMethod(timer = timer)
        case CancelCheckout | ExpireCheckout =>
          cancelled 
        case _ =>
          Behaviors.same
      }
  )

  def selectingPaymentMethod(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receive(
    (context, msg) =>
      msg match {
        case SelectPayment(payment, orderManagerRef) =>
          timer.cancel()
          val paymentRef = context.spawn(new Payment(payment, orderManagerRef, context.self).start, "payment")
          orderManagerRef ! OrderManager.ConfirmPaymentStarted(paymentRef = paymentRef)
          processingPayment(timer = schedulePaymentTimer(context))
        case CancelCheckout | ExpireCheckout =>
          cancelled
        case _ =>
          Behaviors.same
      }
  )

  def processingPayment(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receive(
    (context, msg) =>
      msg match {
        case ConfirmPaymentReceived =>
          timer.cancel()
          cartActor ! TypedCartActor.ConfirmCheckoutClosed
          closed
        case CancelCheckout | ExpirePayment =>
          cartActor ! TypedCartActor.ConfirmCheckoutCancelled
          cancelled
        case _ =>
          Behaviors.same
      }
  )

  def cancelled: Behavior[TypedCheckout.Command] = Behaviors.receive(
    (context, msg) =>
      msg match {
        case _ =>
          Behaviors.stopped
      }
  )

  def closed: Behavior[TypedCheckout.Command] = Behaviors.receive(
    (context, msg) =>
      msg match {
        case _ =>
          Behaviors.stopped
      }
  )

}
