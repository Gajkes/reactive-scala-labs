package EShop.lab3

import EShop.lab2.{Cart, TypedCartActor, TypedCheckout}
import akka.actor.testkit.typed.Effect.{Scheduled, Spawned}
import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, ScalaTestWithActorTestKit, TestInbox}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers


class TypedCartTest
  extends ScalaTestWithActorTestKit
  with AnyFlatSpecLike
  with BeforeAndAfterAll
  with Matchers
  with ScalaFutures {

  override def afterAll: Unit =
    testKit.shutdownTestKit()

  import TypedCartActor._

  //use GetItems command which was added to make test easier
  it should "add item properly" in {
    val testKit = BehaviorTestKit(new TypedCartActor().start)
    val inbox = TestInbox[Cart]()

    testKit.run(TypedCartActor.AddItem("item1"))
    testKit.run(TypedCartActor.GetItems(inbox.ref))
    inbox.expectMessage(Cart(Seq("item1")))
  }

  it should "be empty after adding and removing the same item" in {
    val cartActor = testKit.spawn(new TypedCartActor().start)
    val probe = testKit.createTestProbe[Cart]

    cartActor ! AddItem("item1")
    cartActor ! RemoveItem("item1")
    cartActor ! GetItems(probe.ref)

    probe.expectMessage(Cart(Seq.empty))
  }

  it should "start checkout" in {
    val testKit = BehaviorTestKit(new TypedCartActor().start)
    val inbox = TestInbox[OrderManager.Command]()

    testKit.run(AddItem("item1"))
    testKit.run(StartCheckout(inbox.ref))

    //assert that effects occured
    testKit.expectEffectType[Scheduled[TypedCartActor]]
    testKit.expectEffectType[Spawned[TypedCheckout]]

    val childInbox = testKit.childInbox[TypedCheckout.Command]("typedCheckout")
    childInbox.expectMessage(TypedCheckout.StartCheckout)

    inbox.expectMessage(OrderManager.ConfirmCheckoutStarted(childInbox.ref))
    
  }
}
