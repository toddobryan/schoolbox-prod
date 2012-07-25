package models.users

import java.util.UUID
import javax.jdo.annotations._
import org.datanucleus.api.jdo.query._
import org.datanucleus.query.typesafe._
import scala.collection.immutable.HashSet
import scala.collection.JavaConverters._

import util.{DataStore, ScalaPersistenceManager}

@PersistenceCapable(detachable="true")
class Visit {
  @PrimaryKey
  private[this] var _uuid: String = UUID.randomUUID().toString()
  private[this] var _expiration: Long = _
  private[this] var _user: User = _
  private[this] var _perspective: Perspective = _
  private[this] var _permissions: java.util.Set[Permission] = _
  
  def this(expiration: Long, maybeUser: Option[User], maybePerspective: Option[Perspective]) = {
    this()
    user_=(user)
    expiration_=(expiration)
    perspective_=(perspective)
    permissions_=(new HashSet[Permission]())
  }
  
  def uuid: String = _uuid
  
  def expiration: Long = _expiration
  def expiration_=(theExpiration: Long) { _expiration = theExpiration }
  
  def user: Option[User] = if (_user == null) None else Some(_user)
  def user_=(maybeUser: Option[User]) { _user = maybeUser.getOrElse(null) }
  
  def perspective: Option[Perspective] = if (_perspective == null) None else Some(_perspective)
  def perspective_=(maybePerspective: Option[Perspective]) { _perspective = maybePerspective.getOrElse(null) }
  
  def permissions: Set[Permission] = _permissions.asScala.toSet[Permission]
  def permissions_=(thePermissions: Set[Permission]) { _permissions = thePermissions.asJava }
  
  def isExpired: Boolean = System.currentTimeMillis > expiration
}

object Visit {
  def getByUuid(uuid: String)(implicit pm: ScalaPersistenceManager = null): Option[Visit] = {
    def query(epm: ScalaPersistenceManager): Option[Visit] = {
      epm.query[Visit].filter(QVisit.candidate.uuid.eq(uuid)).executeOption()
    }
    if (pm != null) query(pm)
    else DataStore.withTransaction( tpm => query(tpm) )
  }
  
  def allExpired(implicit pm: ScalaPersistenceManager = null): List[Visit] = {
    def query(epm: ScalaPersistenceManager): List[Visit] = {
      epm.query[Visit].filter(QVisit.candidate.expiration.lt(System.currentTimeMillis)).executeList()
    }
    if (pm != null) query(pm)
    else DataStore.withTransaction( tpm => query(tpm) )
  }
}

trait QVisit extends PersistableExpression[Visit] {
  private[this] lazy val _uuid: StringExpression = new StringExpressionImpl(this, "_uuid")
  def uuid: StringExpression = _uuid
  
  private[this] lazy val _user: ObjectExpression[User] = new ObjectExpressionImpl[User](this, "_user")
  def user: ObjectExpression[User] = _user
  
  private[this] lazy val _expiration: NumericExpression[Long] = new NumericExpressionImpl[Long](this, "_expiration")
  def expiration: NumericExpression[Long] = _expiration
  
  private[this] lazy val _perspective: ObjectExpression[Perspective] = new ObjectExpressionImpl[Perspective](this, "_perspective")
  def perspective: ObjectExpression[Perspective] = _perspective
  
  private[this] lazy val _permissions: CollectionExpression[java.util.Set[Permission], Permission] =
      new CollectionExpressionImpl[java.util.Set[Permission], Permission](this, "_permissions")
  def permissions: CollectionExpression[java.util.Set[Permission], Permission] = _permissions
}

object QVisit {
  def apply(parent: PersistableExpression[_], name: String, depth: Int): QVisit = {
    new PersistableExpressionImpl[Visit](parent, name) with QVisit
  }
  
  def apply(cls: Class[Visit], name: String, exprType: ExpressionType): QVisit = {
    new PersistableExpressionImpl[Visit](cls, name, exprType) with QVisit
  }
  
  private[this] lazy val jdoCandidate: QVisit = candidate("this")
  
  def candidate(name: String): QVisit = QVisit(null, name, 5)
  
  def candidate(): QVisit = jdoCandidate
  
  def parameter(name: String): QVisit = QVisit(classOf[Visit], name, ExpressionType.PARAMETER)
  
  def variable(name: String): QVisit = QVisit(classOf[Visit], name, ExpressionType.VARIABLE)
}