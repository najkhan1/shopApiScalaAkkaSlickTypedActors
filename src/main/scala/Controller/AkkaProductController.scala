package com.najkhan.lightlunch
package Controller

import slick.jdbc.PostgresProfile.api._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import spray.json.{RootJsonFormat, enrichAny}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import model.persistance.ProductRepository

import spray.json.DefaultJsonProtocol._
import model.persistance.ProductRepository.{crtSchma, findProd, getAllOrders, getProduct, presistProduct, saveOrder}
import model.{Basket, BasketRequest, Product, guard}
import service.BasketService.{addToBasket, baskets, createBasket}

import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.util.Timeout
import com.najkhan.lightlunch.Actors.ProdActor
import com.najkhan.lightlunch.Actors.ProdActor.prodAc
import com.najkhan.lightlunch.JsonConversion.JsonConversion

import scala.collection.mutable
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.DurationInt



object AkkaProductController extends App with JsonConversion{

  implicit val system :ActorSystem[prodReqAc] = ActorSystem(ProdActor.apply(), "my-system")

  implicit val executionContext: ExecutionContextExecutor = system.executionContext
  val acref :ActorRef[prodReqAc] = system

  case class prodReq(id :Long)
  case class prodReqAc(id :Long,acRef :ActorRef[guard]) extends guard

  crtSchma

  val prodRoute = {
    concat(
      // get all products
      path("products") {
        get {
          val res = findProd
          onSuccess(res){
            case p@Seq((_,_,_),_*) => {
              var result = List[Product]()
                for(prod <- p){
                  println(prod)
                  result = result :+ getProduct(prod)
                }
              println(result.length)
              complete(StatusCodes.OK,result)
            }
            case _ => println(res);complete(StatusCodes.OK,"Nothing here")
          }
        }
      },
    pathPrefix("product") {
      //get product using product id
      path(Segment){ req =>
       get {
         implicit val timeout: Timeout = 5.seconds

         val prod =  system.ask( ref =>prodReqAc(req.toLong,ref))
          onSuccess(prod) {
                           case prodAc(value) =>
                             onSuccess(value) {
                               case p@Product(_,_,_) => complete(StatusCodes.OK, p)
                             }
                           case _ => complete(StatusCodes.NotFound, "No such product")
                         }

            }
          }
      },
      // persist a product
      pathPrefix("product"){
        put{
          entity(as[Product]){
            req => {
              onSuccess(presistProduct(req))(_ => complete(StatusCodes.OK, s"Successfull peristed the product with id ${req.id}"))
            }
          }
        }
      }
    )
    }
  val basketRoute = {
    concat(
      // create basket
      path("baskets") {
        post {
          entity(as[BasketRequest]) {
            baskReq => {
              createBasket(baskReq)
            }
          }
        }
      },
      // get basket using basket UUID
      path("baskets"){
         get {
             complete(StatusCodes.OK,baskets.values)
           }
       },
      pathPrefix("baskets"){
                              path(Segment){ req =>
                                put {
                                  entity(as[BasketRequest]){ br =>
                                        addToBasket(req,br)
                                  }
                                }
                              }
      }
    )
  }
  val orderRoute = {

      pathPrefix("order") {
        path(Segment) { basId =>
          post {
            saveOrder(basId,baskets.get(basId))
            complete(StatusCodes.OK,"Order Persisted")
          }
        }
      }
  }
  val allOrders = {
    path("orders") {
      get {
        val orders = getAllOrders()
        orders.onComplete{println(_)}
        onSuccess(orders) {
          case b :mutable.Map[String,Basket] =>
            //closeDBConn()
            complete(StatusCodes.OK, b.values)
          case _ => complete(StatusCodes.OK,"Nothing to show")
        }
      }
    }
  }
  val allRoutes = concat(prodRoute,basketRoute,orderRoute,allOrders)

  Http().newServerAt("0.0.0.0", 8082).bind(allRoutes)

  }





