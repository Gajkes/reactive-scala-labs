package EShop.lab5

import akka.Done
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import spray.json.{DefaultJsonProtocol, JsString, JsValue, JsonFormat}

import java.net.URI
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration.{Duration, DurationInt}

import akka.actor.typed.receptionist.Receptionist.Listing

trait JsonSupportProductCatalog extends SprayJsonSupport with DefaultJsonProtocol {

  //custom formatter just for example
  implicit lazy val uriFormat = new JsonFormat[java.net.URI] {
    override def write(obj: java.net.URI): spray.json.JsValue = JsString(obj.toString)
    override def read(json: JsValue): URI =
      json match {
        case JsString(url) => new URI(url)
        case _             => throw new RuntimeException("Parsing exception")
      }
  }

  implicit val itemFormat  = jsonFormat5(ProductCatalog.Item)
  implicit val itemsFormat = jsonFormat1(ProductCatalog.Items)

}

case class ProductCatalogRest(productCatalog: ActorRef[ProductCatalog.Query])(implicit val scheduler: Scheduler)
  extends JsonSupportProductCatalog {
  implicit val timeout: Timeout = Timeout(5.seconds)

  def routes: Route = path("products") {
    get {
      parameters("brand".as[String], "keywords".as[String]) { (brand, words) =>
        val items: Future[ProductCatalog.Items] =
          productCatalog
            .ask(ref => ProductCatalog.GetItems(brand, words.split(" ").toList, ref))
            .mapTo[ProductCatalog.Items]

        onSuccess(items) { items =>
          complete(items)
        }
      }
    }
  }
}
object ProductCatalogRestServer {
  def apply(port: Int): Behavior[Receptionist.Listing] =
    Behaviors.setup { context =>
      // Register the ProductCatalogRest actor with the receptionist
      context.system.receptionist ! Receptionist.subscribe(ProductCatalog.ProductCatalogServiceKey, context.self)

      implicit val timeout: Timeout             = 3.second
      implicit val scheduler: Scheduler         = context.system.scheduler
      implicit val system: ActorSystem[Nothing] = context.system

      Behaviors.receiveMessage[Receptionist.Listing] { msg =>
        val listing =
          msg.serviceInstances(ProductCatalog.ProductCatalogServiceKey)
        if (listing.isEmpty) {
          Behaviors.same
        } else {
          val queryRef = listing.head
          val rest     = ProductCatalogRest(queryRef)
          val binding =
            Http().newServerAt("localhost", port).bind(rest.routes)
          val result = Await.ready(binding, Duration.Inf)
          Behaviors.empty
        }
      }

    }

  def start(port: Int): Future[Done] = {
    val system = ActorSystem[Receptionist.Listing](ProductCatalogRestServer(port), "ProductCatalog")
    val config = ConfigFactory.load()

    val productCatalogSystem = ActorSystem[Nothing](
      Behaviors.empty,
      "ProductCatalog",
      config.getConfig("productcatalog").withFallback(config)
    )
    productCatalogSystem.systemActorOf(ProductCatalog(new SearchService()), "productcatalog")

    Await.ready(system.whenTerminated, Duration.Inf)

  }
}

object ProductCatalogRestApp extends App {
  ProductCatalogRestServer.start(9000)
}
