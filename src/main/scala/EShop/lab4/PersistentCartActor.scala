package EShop.lab4

import EShop.lab2.{Cart, TypedCheckout}
import EShop.lab3.OrderManager
import akka.actor.Cancellable
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.concurrent.duration._
import akka.actor.typed.ActorRef

class PersistentCartActor {

  import EShop.lab2.TypedCartActor._

  val cartTimerDuration: FiniteDuration = 5.seconds

  private def scheduleTimer(context: ActorContext[Command]): Cancellable =
    context.scheduleOnce(delay = cartTimerDuration, target = context.self, ExpireCart)

  def apply(persistenceId: PersistenceId): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId,
      Empty,
      commandHandler(context),
      eventHandler(context)
    )
  }

  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) => {
    state match {
      case Empty =>
        command match {
          case AddItem(item)    => addItem(state, item)
          case GetItems(sender) => Effect.reply(sender)(Cart.empty)
          case _                => Effect.none
        }

      case NonEmpty(cart, _) =>
        command match {
          case AddItem(item)                  => addItem(state, item)
          case RemoveItem(item)               => removeItem(state, item)
          case ExpireCart                     => Effect.persist(CartExpired)
          case StartCheckout(orderManagerRef) => startCheckout(context, orderManagerRef)
          case GetItems(sender)               => Effect.reply(sender)(cart)
          case _                              => Effect.none
        }

      case InCheckout(_) =>
        command match {
          case ConfirmCheckoutCancelled => Effect.persist(CheckoutCancelled)
          case ConfirmCheckoutClosed    => Effect.persist(CheckoutClosed)
          case GetItems(sender)         => Effect.reply(sender)(state.cart)
          case _                        => Effect.none
        }
    }
  }

  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) => {
    event match {
      case CheckoutStarted(_)        => InCheckout(state.cart)
      case ItemAdded(item)           => NonEmpty(state.cart.addItem(item), scheduleTimer(context))
      case ItemRemoved(item)         => NonEmpty(state.cart.removeItem(item), scheduleTimer(context))
      case CartEmptied | CartExpired => Empty
      case CheckoutClosed            => Empty
      case CheckoutCancelled         => NonEmpty(state.cart, scheduleTimer(context))
    }
  }

  private def addItem(state: State, item: Any): Effect[Event, State] = {
    state match {
      case Empty                 => Effect.persist(ItemAdded(item))
      case NonEmpty(cart, timer) => Effect.persist(ItemAdded(item))
      case _                     => Effect.none
    }
  }

  private def removeItem(state: State, item: Any): Effect[Event, State] = {
    state match {
      case Empty => Effect.none
      case NonEmpty(cart, timer) if cart.contains(item) && cart.items.size == 1 =>
        Effect.persist(CartEmptied)
      case NonEmpty(cart, timer) if cart.contains(item) =>
        Effect.persist(ItemRemoved(item))
      case NonEmpty(_, _) => Effect.none
      case _              => Effect.none
    }
  }

  private def startCheckout(
    context: ActorContext[Command],
    orderManagerRef: ActorRef[OrderManager.Command]
  ): Effect[Event, State] = {
    val checkoutActor = context.spawn(new TypedCheckout(context.self).start, "typedCheckout")
    Effect.persist(CheckoutStarted(checkoutActor)).thenRun { _ =>
      checkoutActor ! TypedCheckout.StartCheckout
      orderManagerRef ! OrderManager.ConfirmCheckoutStarted(checkoutActor)
    }

  }

}
