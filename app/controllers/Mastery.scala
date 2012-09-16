package controllers

import play.api._
import scala.collection.JavaConverters._
import models.users.Visit
import util.Helpers.mkNodeSeq
import scala.util.Random
import play.api.mvc._
import util.{ DataStore, ScalaPersistenceManager }
import util.DbAction
import forms._
import forms.fields._
import forms.validators.Validator
import forms.validators.ValidationError
import play.api.mvc.PlainResult
import util.DbRequest
import math._
import play.api.templates.Html
import scala.collection.immutable.HashMap
import play.api.data._
import play.api.data.Forms._
import views._
import models._
import play.api.data.validation.Constraints._
import models.mastery._
import forms.Form
import forms.fields._
import models.assignments.questions.FillBlanks
import play.api.mvc.{ Action, Controller, Session }
import play.api.data.Forms._
import play.api.templates.Html
import views.html
import util.DataStore
import util.ScalaPersistenceManager
import util.{ DbAction, DbRequest, Menu }
import forms.Form
import forms.fields._
import forms.widgets._
import forms.{ Binding, InvalidBinding, ValidBinding }
import forms.validators.ValidationError
import forms.validators.Validator
import util.Authenticated
import scala.xml._
import scala.xml
import util.Helpers.camel2TitleCase

object Mastery extends Controller {

  def menuOfTests() = DbAction { implicit req =>
    val pm = req.pm
    val cand = QQuiz.candidate()
    val listOfMasteries = pm.query[Quiz].orderBy(cand.name.asc).executeList()
    val hasQuizzes = listOfMasteries.size != 0
    val table: List[NodeSeq] = listOfMasteries.map { q =>
      <tr>
        <td>{ linkToQuiz(q) }</td>
      </tr>
    }

    Ok(html.tatro.mastery.MasteryQuizMenu(table, hasQuizzes)) // this is a fake error -.-
  }

  def linkToQuiz(quiz: Quiz): NodeSeq = {
    val link = controllers.routes.Mastery.getDisplayQuiz(quiz.id)
    <a href={ link.url }>{ quiz.toString }</a>
  }

  def getDisplayQuiz(quizId: Long) = DbAction { implicit req =>
    implicit val pm: ScalaPersistenceManager = req.pm
    displayQuiz(Quiz.getById(quizId))
  }

  def displayQuiz(maybeQuiz: Option[Quiz])(implicit request: DbRequest[_]): PlainResult = {
    if (!maybeQuiz.isDefined) {
      NotFound(views.html.notFound("The quiz of which you are seeking no longer exists."))
    } else {
      val quiz = maybeQuiz.get
      val sections: List[QuizSection] = quiz.sections
      if (sections == null || sections.isEmpty) {
        NotFound(views.html.notFound("There are no sections :("))
      } else {
        var SAndQ: Map[QuizSection, List[Question]] = Map()
        var LQ = List[Question]()
        if (request.method == "GET") {
          for (s <- sections) {
            SAndQ += (s -> s.randomQuestions) //SAndQ id a map of Section -> List[Question] 
          }
          SAndQ.keys.foreach { k =>
            for (q <- SAndQ(k)) {
              LQ = q :: LQ
            }
          }
          request.visit.updateListOfQuestions(LQ)
        } else {
          LQ = request.visit.SAndQ
        }

        //MasteryForm uses SAndQ
        if (request.method == "GET") {
          object MasteryForm extends Form {

            var sectionInstructionList: List[String] = {
              var tempList = List[String]()
              for (sq <- SAndQ) {
                tempList = sq._1.toString :: tempList
              }
              tempList
            }
            def getsectionInstructions = { sectionInstructionList }
            val fields2: List[List[forms.fields.Field[_]]] = {
              var tempList = List[List[forms.fields.Field[_]]]()
              for (sq <- SAndQ) {
                var tempList2 = List[forms.fields.Field[_]]()
                for (q <- sq._2) {
                  tempList2 = (new TextField(q.toString())) :: tempList2
                }
                tempList = tempList2 :: tempList
              }
              tempList
            }

            def getfields: List[List[forms.fields.Field[_]]] = { fields2 }

            override def asHtml(bound: Binding): Elem = {
              <form method={ method }>
                <table>
                  { if (bound.formErrors.isEmpty) NodeSeq.Empty else <tr><td></td><td>{ bound.formErrors.asHtml }</td><td></td></tr> }
                  {
                    fields2.flatMap(q => {
                      //TODO: Make it so the strings in the list "sectionInstructionList" appear
                      <tr>
                        <td>{ sectionInstructionList.apply(0).toString }</td>
                      </tr>
                      sectionInstructionList = sectionInstructionList.drop(1)
                      q.flatMap(f => {
                        val name = f.name
                        val label = f.label.getOrElse(camel2TitleCase(f.name))
                        val labelName = if (label == "") "" else {
                          if (":?.!".contains(label.substring(label.length - 1, label.length))) label
                          else label + labelSuffix
                        }
                        val labelPart =
                          if (labelName != "") f.labelTag(this, Some(labelName)) ++ scala.xml.Text(" ")
                          else NodeSeq.Empty
                        val errorList = bound.fieldErrors.get(name).map(_.asHtml)
                        <tr>
                          <td>{ labelPart }</td>
                          <td>{ f.asWidget(bound) }</td>
                          {
                            if (bound.hasErrors) <td>{ errorList.getOrElse(NodeSeq.Empty) }</td>
                            else NodeSeq.Empty
                          }
                        </tr>
                      })
                    }).toList
                  }
                </table>
                <input type="submit"/>
              </form>
            }
            val fields = List[forms.fields.Field[_]]()
          }
          Ok(html.tatro.mastery.displayMastery(quiz, Binding(MasteryForm)))
        } else {
          object MasteryForm extends Form {
            val fields: List[forms.fields.Field[_]] = {
              var tempList = List[forms.fields.Field[_]]()
              for (q <- LQ) {
                tempList = new TextField(q.toString()) :: tempList
              }
              tempList
            }
          }
          Binding(MasteryForm, request) match {
            case ib: InvalidBinding => Ok(html.tatro.mastery.displayMastery(quiz, ib)) // there were errors
            case vb: ValidBinding => {
              var listAnswers = List[String]()
              for (f <- MasteryForm.fields) {
                listAnswers = vb.valueOf(f).toString() :: listAnswers
              }
              //save these in Visit
              request.visit.updateQuiz(quiz)
              request.visit.updateLQ(LQ)
              request.visit.updateLA(listAnswers)
              Redirect(routes.Mastery.checkAnswers())
            }
          }
        }
      }
    }
  }

  def testDataBase() = DbAction { implicit req =>
    //val pm=req.pm
    val quizCand = QQuiz.candidate()
    val listOfMasteries = req.pm.query[Quiz].orderBy(quizCand.name.asc).executeList()
    val listOfSections = req.pm.query[models.mastery.QuizSection].executeList()
    val listOfQSets = req.pm.query[QuestionSet].executeList()
    val listOfQuestions = req.pm.query[Question].executeList()
    Ok(html.tatro.mastery.testData(listOfMasteries, listOfSections, listOfQSets, listOfQuestions))
  }

  def checkAnswers() = DbAction { implicit request =>
    val quiz: Quiz = request.visit.getQuiz
    val questionList: List[Question] = request.visit.getLQ
    val answerList: List[String] = request.visit.getLA
    var ScoreInTF = List[Boolean]()
    var questionList2 = questionList
    for (num <- 0 to answerList.size-1) {
      val a = answerList.apply(num)
      val c = changeToInterpreterSyntax(a)
      val reorderedAns = reorderAnswer(c)
      if(reorderedAns.equals(answerList.apply(num))){
        ScoreInTF = true :: ScoreInTF
      } else {
        ScoreInTF = false :: ScoreInTF
      }
      ScoreInTF = ScoreInTF.reverse
    }
    var numberWrong = 0
    for (correct <- ScoreInTF) {
      if (!correct) numberWrong = numberWrong + 1
    }
    Ok(html.tatro.mastery.displayScore(quiz, questionList, answerList, ScoreInTF, numberWrong))
  }

  def getRidOfSpaces(s: String) = """ """.r.replaceAllIn(s, "")
  def getRidOfExtraMultiplication(s: String) = {
    var rString1 = ""
    for (n <- 1 to s.length - 1) {
      val c = s.charAt(n)
      val pc = s.charAt(n - 1)
      if (pc == '*' && n - 2 >= 0) {
        if ((c.isLetter && s.charAt(n - 2).isLetter) || (c.isDigit && s.charAt(n - 2).isDigit)) {
          rString1 = rString1 + pc
        }
      } else {
        rString1 = rString1 + pc
      }
      if (n == s.length() - 1 && (c.isDigit || c.isLetter || c == ')')) {
        rString1 = rString1 + c
      }
    }
    System.out.println(rString1)
    rString1
  }

  def changeRadToR(s: String) = """rad""".r.replaceAllIn(s, "r")

  def encloseExponents(s: String) = {
    var rs = ""
    rs = rs + s.charAt(0)
    var inExponent = false
    for (n <- 1 to s.length - 1) {
      val c = s.charAt(n)
      if (n != s.length - 1 && c == '^' && s.charAt(n + 1) != '(') {
        rs = rs + "^("
        inExponent = true
      } else if (inExponent && (!c.isLetter && !c.isDigit)) {
        rs = rs + ")" + c
        inExponent = false
      } else if(inExponent && n==s.length-1){
        rs = rs + c + ")"
      } else {
        rs = rs + c
      }
    }
    rs
  }

  def changeToInterpreterSyntax(s: String) = {
    var rs = getRidOfSpaces(s)
    rs = encloseExponents(rs)
    rs = changeRadToR(rs)
    rs = getRidOfExtraMultiplication(rs)
    rs
  }
  private[this] var mapIndex: Int = _
  def reorderAnswer(s: String) = { //TODO: this needs some work dealing with parens... I think
    mapIndex = 0
    mapOfreplacements = Map(1.toChar + "" -> 1.toChar)
    var ParentParen = List[String]()
    if (hasPerfectParens(s)) {
      ParentParen = getOnlyThingsInParens(s)
      if (!(ParentParen == Null))
        for (p <- ParentParen) {
          mapParenGroupsToChars(p)
        }
    }
    val ns = replaceParensWithChars(s) // i.e. (x+2) -> a and r(x+3) -> rb
    System.out.println("replace Parens With Chars: \n" + ns)
    val ns2 = replaceRwithChar(ns) 
    //TODO: this needs work i.e. rb -> c 
    System.out.println("replace r with Chars: \n"+ns2) 
    val ns3 = replaceExponentsWithChar(ns2) 
    //TODO: this needs work i.e. d^f -> e 
    System.out.println("replace Exponents With Chars: \n"+ns3)
    val ns4 = reorderExpression(ns3)
    System.out.println("reorder Expression: \n"+ns4)
    System.out.println("map of substitutions before reorder: \n" + mapOfreplacements)
    mapOfreplacements.keys.foreach{ k=> 
    //TODO: Fix This
    	val split = k.split("((?<=[()+\\-*/])|(?=[()+\\-*/]))").toList
    	var remadeKey = ""
    	for(q <- split){
    	  remadeKey = remadeKey + reorderMultiplication(q)
    	}
    	remadeKey = reorderAddMinus(remadeKey)
    	mapOfreplacements += (remadeKey -> mapOfreplacements(k))
    	mapOfreplacements -= (k)
    }
    System.out.println("map of substitutions after reorder: \n"+mapOfreplacements)
    val ns5 = replaceCharsWithStr(ns4)
    System.out.println("substitute parens back in: \n"+ns5)
    System.out.println()
    System.out.println()
    System.out.println()
    ns5 
  }
  
  def replaceCharsWithStr(s: String): String = {
    var ns = s
    var tf = true
    while(tf) {
      tf = false
      mapOfreplacements.keys.foreach { str =>
        if (ns.indexOf(mapOfreplacements(str)) != -1) {
          ns = ns.replace(mapOfreplacements(str).toString, str)
          tf = true
        }
      }
    }
    ns
  }

  def replaceExponentsWithChar(s: String) = {
    var ns = s
    for (n <- 1 to s.length - 2) {
      val c = s.charAt(n)
      val nc = s.charAt(n + 1)
      val pc = s.charAt(n - 1)
      if (c == '^') {
        mapOfreplacements += (pc.toString + c.toString + nc.toString -> (mapIndex + 1).toChar)
        mapIndex = mapIndex + 1
        ns.replace(pc.toString + c.toString + nc.toString, mapIndex.toChar.toString)
      }
    }
    ns
  }

  
  def reorderExpression(s: String) = {
    val splitOnOperands = s.split("((?<=[+\\-*/])|(?=[+\\-*/]))").toList
    var correctMultOrder = ""
    for (s1 <- splitOnOperands) {
      correctMultOrder = correctMultOrder + reorderMultiplication(s1)
    }
    val correctOrder = reorderAddMinus(correctMultOrder)
    correctOrder
    }
  
  def reorderAddMinus(s: String) = {
    val tempList = s.split("(?=[+\\-])").toList
    var tempList2 = s.split("[+\\-]").toList
    tempList2=tempList2.sorted
    var tempList3 = List[String]()
    for(num <- 0 to tempList2.size-1){
      val str = tempList2.apply(num)
      var opperator = ""
      for(str2 <- tempList){
        if(str2.contains(str) && (str2.contains("+") || str2.contains("-"))){
          opperator = str2.charAt(0).toString
        } else {
          opperator = "+"
        }
      }
      tempList3 = opperator+str :: tempList3
    }
    val str = tempList3.mkString
    val rstr = str.substring(1)
    rstr
  }

  def replaceRwithChar(s: String) = {
    var ns = s
    for (n <- 0 to s.length - 2) {
      val c = s.charAt(n)
      val nc = s.charAt(n + 1)
      if (c == 'r') {
        mapOfreplacements += (c.toString + nc.toString -> (mapIndex + 1).toChar)
        mapIndex = mapIndex + 1
        ns.replace(c.toString + nc.toString, mapIndex.toChar.toString)
      }
    }
    ns
  }

  def reorderMultiplication(s: String) = {
    if (!(s.equals("+") || s.equals("-") || s.equals("*") || s.equals("/") || s.equals("(") || s.equals(")"))) {
      var ns = s
      var eachElement = List[String]()
      for (n <- 0 to s.length - 1) {
        if (ns.length != 0) {
          val c = ns.charAt(0)
          if (!c.isDigit) {
            eachElement = c.toString :: eachElement
            ns = ns.substring(1)
          } else {
            var endNumberIndex = 0
            var notFoundEnd = true
            for (i <- 0 to ns.length - 1) {
              val cc = ns.charAt(i)
              if ((!cc.isDigit) && notFoundEnd) {
                endNumberIndex = i - 1
              } else if (i == ns.length - 1) {
                endNumberIndex = i
              }
            }
            val str = ns.substring(0, endNumberIndex + 1)
            eachElement = str :: eachElement
            ns = ns.substring(endNumberIndex + 1)
          }
        }
      }
      eachElement = eachElement.sorted
      val str = eachElement.mkString
      str
    } else s
  }
  
  object Integer {
    def unapply(s: String) : Option[Int] = try {
      Some(s.toInt)
    } catch {
      case _ : java.lang.NumberFormatException => None
    }
  }
  
  def isNum(str: String): Boolean = str match {
    case Integer(x) => true
    case _ => false
  }
  
  def remove[T](elem: T, list: List[T]) = list diff List(elem)

  def replaceParensWithChars(s: String) = {
    var ns = s
    var x = 2
    var tf = true
    for (i <- 1 to mapOfreplacements.size + 10) {
      tf = false
      mapOfreplacements.keys.foreach { str =>
        if (ns.indexOf(str) != -1) {
          ns = ns.replace(ns.substring(ns.indexOf(str), ns.indexOf(str) + (str.length)), mapOfreplacements(str).toString)
          tf = true
        }
      }
    }
    ns
  }

  def splitKeepingFirst(s: String, c: Char) = { //splits into a List that keeps c on the front of each element
    val newList1 = s.split(c).toList
    var newList2 = List[String]()
    newList2 = newList1.apply(0) :: newList2
    for (n <- 1 to newList1.size - 1) {
      val p = newList1.apply(n)
      newList2 = c + p :: newList2
    }
    newList2 = newList2.reverse
    newList2
  }

  def splitKeepingLast(s: String, c: Char) = { //splits into a List that keeps c on the end of each previous element
    val newList1 = s.split(c).toList
    var newList2 = List[String]()
    for (n <- 0 to newList1.size - 2) {
      val p = newList1.apply(n)
      newList2 = p + c :: newList2
    }
    newList2 = newList1.apply(newList1.size - 1) :: newList2
    newList2 = newList2.reverse
    newList2
  }

  private[this] var mapOfreplacements: Map[String, Char] = _

  def hasPerfectParens(s: String) = { //tells if the String expression has perfect parentheses
    var tf = true
    var inParens = 0
    if (s.length <= 1) {
      tf = false
    }
    for (c <- s) {
      if (c == '(') {
        inParens = inParens + 1
      }
      if (c == ')') {
        inParens = inParens - 1
      }
      if (inParens < 0) {
        tf = false
      }
    }
    if (inParens != 0) {
      tf = false
    }
    tf
  }

  def getOnlyThingsInParens(s: String) = { //returns a list of the Parent Parentheses Groups
    var rString1 = ""
    var inParens = 0
    for (c <- s) {
      if (c == '(') {
        inParens = inParens + 1
      }
      if (inParens != 0) {
        rString1 = rString1 + c
      }
      if (c == ')') {
        inParens = inParens - 1
      }
    }
    val ListOfParentParenGroups = splitParentParens(rString1)
    ListOfParentParenGroups.reverse
  }

  def splitParentParens(s: String) = {
    var inParens = 0
    var listOfString = List[String]()
    var start = 0
    for (n <- 0 to s.length() - 1) {
      val c = s.charAt(n)
      if (c == '(') {
        inParens = inParens + 1
        if (inParens == 1) {
          start = n
        }
      }
      if (c == ')') {
        inParens = inParens - 1
        if (inParens == 0) {
          listOfString = s.substring(start, n + 1) :: listOfString
        }
      }
    }
    listOfString
  }

  def mapParenGroupsToChars(s: String) = {
    mapOfreplacements = mapOfreplacements ++ mapParentheses(s)
    mapIndex = mapIndex + mapOfreplacements.size
  }

  def str2parens(s: String): (Parens, String) = {
    def fail = throw new Exception("Wait, this system was made to be unflawed!!! How did you... Just HOW!?")
    if (s(0) != '(') fail
    def parts(s: String, found: Seq[Part] = Vector.empty): (Seq[Part], String) = {
      if (s(0) == ')') (found, s)
      else if (s(0) == '(') {
        val (p, s2) = str2parens(s)
        parts(s2, found :+ p)
      } else {
        val (tx, s2) = s.span(c => c != '(' && c != ')')
        parts(s2, found :+ new Text(tx))
      }
    }
    val (inside, more) = parts(s.tail)
    if (more(0) != ')') fail
    (new Parens(inside), more.tail)
  }

  def findParens(p: Parens): Set[Parens] = {
    val inside = p.contents.collect { case q: Parens => findParens(q) }
    inside.foldLeft(Set(p)) { _ | _ }
  }

  def mapParentheses(s: String) = {
    val (p, _) = str2parens(s)
    val pmap = findParens(p).toSeq.sortBy(_.text.length).zipWithIndex.toMap
    val p2c = pmap.mapValues(i => (i + 500 + mapIndex).toChar)
    p2c.map { case (p, c) => (p.mapText(p2c), c) }.toMap
  }
}

trait Part {
  def text: String
  override def toString = text
}
class Text(val text: String) extends Part {}
class Parens(val contents: Seq[Part]) extends Part {
  val text = "(" + contents.mkString + ")"
  def mapText(m: Map[Parens, Char]) = {
    val inside = contents.collect {
      case p: Parens => m(p).toString
      case x => x.toString
    }
    "(" + inside.mkString + ")"
  }
  override def equals(a: Any) = a match {
    case p: Parens => text == p.text
    case _ => false
  }
  override def hashCode = text.hashCode
}



