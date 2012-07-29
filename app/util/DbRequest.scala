package util

import play.api.mvc.{Request, WrappedRequest}
import models.users.{Perspective, User, Visit, QVisit}
import play.api.mvc.Result
import play.api.mvc.BodyParser
import play.api.mvc.BodyParsers.parse
import play.api.mvc.Action
import play.api.mvc.AnyContent
import models.users.Visit
import play.api.mvc.PlainResult
import javax.jdo.JDOHelper
import play.api.mvc.Controller
import play.api.mvc.Results
import controllers.routes

class DbRequest[A](val request: Request[A]) extends WrappedRequest[A](request) {
  implicit val pm = DataStore.getPersistenceManager()
  implicit val visit = request.session.get("visit").flatMap(
      Visit.getByUuid(_)).filter(!_.isExpired).getOrElse{
    pm.deletePersistentAll(Visit.allExpired)
    new Visit(System.currentTimeMillis + DbRequest.sessionLength, None, None)
  }
}

object DbRequest {
  val sessionLength = 3600000
}

// TODO: we need a cache system
object DbAction {
  def apply[A](p: BodyParser[A])(f: DbRequest[A] => PlainResult) = {
    Action(p) ( request => {
      val dbReq = new DbRequest[A](request)
      dbReq.pm.beginTransaction()
      val res = f(dbReq)
      if (JDOHelper.isDeleted(dbReq.visit)) {
        dbReq.pm.commitTransactionAndClose()
        res.withNewSession
      } else {
        dbReq.visit.expiration = System.currentTimeMillis + DbRequest.sessionLength
        dbReq.pm.makePersistent(dbReq.visit)
        dbReq.pm.commitTransactionAndClose()
        if (request.session.get(dbReq.visit.uuid).isDefined) res
        else res.withSession("visit" -> dbReq.visit.uuid)
      }
    })
  }

  def apply(f: DbRequest[AnyContent] => PlainResult) = {
    apply[AnyContent](parse.anyContent)(f)
  }
}

object Authenticated {
  def apply(f: DbRequest[AnyContent] => PlainResult) = DbAction( req => {
	req.visit.user match {
	  case None => Results.Redirect(routes.Users.login()).flashing("message" -> "You must log in to view that page.")
	  case _ => f(req)
	}
  })
}

object Method {
  val GET = "GET"
  val POST = "POST"
}