import play.api._
import com.google.inject.{ Guice, Injector }
import config.ConfigInjector
import config.users.ProvidesInjector
import models.users.Group
import models.books.Book
import config.users.UsesDataStore

object Global extends GlobalSettings with ProvidesInjector with UsesDataStore {
  lazy val injector = Guice.createInjector(new ConfigInjector())
 
  def provideInjector(): Injector = Guice.createInjector(new ConfigInjector())
  
  override def getControllerInstance[A](klass: Class[A]) = {
    injector.getInstance(klass)
  }
  
  override def onStart(app: Application) {
    // Create Groups
    dataStore.withTransaction { pm => 
      val teacher: Group = Group("teacher")
      val student: Group = Group("student")
      val guardian: Group = Group("guardian")
      // Assign default permissions to groups
      teacher.addPermission(Book.Permissions.LookUp)
    }
  }
}