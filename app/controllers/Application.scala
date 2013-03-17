package controllers

import play.api._
import forms._
import forms.fields._
import forms.validators._
import forms.widgets._
import play.api.mvc._
import util.{DataStore, ScalaPersistenceManager}
import util.DbAction

object Application extends Controller {

  object formTests extends Form{
    //val BooleanField = new BooleanField("Boolean")
    val ChoiceField = new ChoiceFieldOptional("Choice", List(("hi", "hi"),("bye","bye")))
    val DateField = new DateFieldOptional("Date")
    val TimeField = new TimeFieldOptional("Time")
    val TimestampField = new TimestampFieldOptional("Timestamp")
    val EmailField = new EmailFieldOptional("Email")
    val NumericField = new NumericFieldOptional[Double]("Double")
    val PasswordField = new PasswordFieldOptional("Password")
    val TextField = new TextFieldOptional("Text")
    val UrlField = new UrlFieldOptional("Url")
    
    val editedTextField = new TextFieldOptional("edited") {      
      override def widget = new TextInput(required)
      
      override def helpText = Some(<p>Please input "lolCats" for true</p><p>here is a reset button: <button type="reset" class="btn">useless</button></p>)
      
      override def asValue(s: Seq[String]): Either[ValidationError, Option[String]] = {
        s match {
        case Seq() => Right(None)
        case Seq(str) => if(str=="lolCats") Right(Some("true")) else Right(Some("false"))
        case _ => Left(ValidationError("Expected a single value, got multiples."))
    }
      }
    }
    
    
    val fields = List(ChoiceField, DateField,TimeField, TimestampField, EmailField, NumericField, PasswordField, TextField, UrlField, editedTextField)
    
    override def cancelTo: String = "url"
    override def prefix: Option[String] = None
    override def submitText = "Submit"
    override def includeCancel = true
    override def cancelText = "Cancel"
    
  }
  
  def index() = DbAction { implicit req =>
    Ok(views.html.index())
  }

  def stub() = DbAction { implicit req => 
    Ok(views.html.stub())
  }
  
  def formTest() = DbAction { implicit req =>
    if(req.method=="GET") Ok(views.html.formTester(Binding(formTests)))
    else Binding(formTests, req) match {
      case ib: InvalidBinding => Ok(views.html.formTester(ib))
      case vb: ValidBinding => {
        val TheChoice = vb.valueOf(formTests.ChoiceField)
        val TheDate = vb.valueOf(formTests.DateField)
        val TheTime = vb.valueOf(formTests.TimeField)
        val TheTimestamp = vb.valueOf(formTests.TimestampField)
        val TheEmail = vb.valueOf(formTests.EmailField)
        val TheNumeric = vb.valueOf(formTests.NumericField)
        val ThePassword = vb.valueOf(formTests.PasswordField)
        val TheText = vb.valueOf(formTests.TextField)
        val TheUrl = vb.valueOf(formTests.UrlField)
        val TheEdited = vb.valueOf(formTests.editedTextField)
        val ListOfStuff = List(("Choice Field", TheChoice.toString), ("Date Field", TheDate.toString), ("Time Field", TheTime.toString), ("Timestamp Field", TheTimestamp.toString), ("Email Field", TheEmail.toString), ("NumericField", TheNumeric.toString), ("Password Field", ThePassword.toString), ("Text Field", TheText.toString), ("Url Field", TheUrl.toString), ("Edited Field", TheEdited.toString))
        
        Ok(views.html.showResults(ListOfStuff))
      }
    }
  }
  
}
