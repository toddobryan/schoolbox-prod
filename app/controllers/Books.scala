package controllers

import play.api._
import play.api.mvc._
import util.{DataStore, ScalaPersistenceManager}
import util.DbAction
import models.books._
import models.users._
import forms._
import forms.fields._
import views.html
import forms.validators.Validator
import forms.validators.ValidationError
import javax.imageio._
import java.io._
import org.datanucleus.api.jdo.query._
import org.datanucleus.query.typesafe._
import com.itextpdf.text.pdf.{Barcode128, Barcode, PdfContentByte, PdfWriter, BaseFont}
import com.itextpdf.text.{BaseColor, Document, DocumentException, PageSize, Paragraph, Utilities}

object Books extends Controller {
  /**
   * Given a list of the first 9 digits from a ten-digit ISBN,
   * returns the expected check digit (which could also be an X in
   * addition to the digits 0 through 9). The algorithm can be found here: 
   * http://en.wikipedia.org/wiki/International_Standard_Book_Number#Check_digits
   */
  def tenDigitCheckDigit(digits: List[Int]): String = {
    val checkSum = digits.zipWithIndex.map(digitWithIndex => {
      val digit = digitWithIndex._1
      val index = digitWithIndex._2
      (10 - index) * digit
    }).sum
    val checkDigit = (11 - (checkSum % 11)) % 11
    if (checkDigit == 10) "X" else checkDigit.toString
  }
  
  /**
   * Given a list of the first 12 digits from a 13-digit ISBN,
   * returns the expected check digit. The algorithm can be found
   * here:
   * http://en.wikipedia.org/wiki/International_Standard_Book_Number#Check_digits
   */
  def thirteenDigitCheckDigit(digits: List[Int]): String = {
    val checkSum = digits.zipWithIndex.map(digitWithIndex => {
      val digit = digitWithIndex._1
      val index = digitWithIndex._2
      digit * (if ((index % 2) == 0) 1 else 3)
    }).sum
    ((10 - (checkSum % 10)) % 10).toString
  }
  
  /**
   * Given a possible ISBN (either 10- or 13-digit) with the check
   * digit removed, calculates the check digit, if possible. If the
   * given String is not the right length or has illegal characters,
   * returns None.
   */
  def checkDigit(isbn: String): Option[String] = {
    // if (isbn.matches("^\\d+$")) {
    try {
      val digits = isbn.toList.map(_.toString.toInt)
      digits.length match {
        case 9 => Some(tenDigitCheckDigit(digits))
        case 12 => Some(thirteenDigitCheckDigit(digits))
        case _ => None
      }
    }
    catch {
      case _: NumberFormatException => None
    }
    // } else None
  }

  /**
   * Converts a valid 10-digit ISBN into the equivalent 13-digit one.
   * If the original String is not valid, may cause an exception.
   */
  def makeIsbn13(isbn10: String): String = {
    val isbn9 = isbn10.substring(0, 9)
    val isbn12 = "978" + isbn9
    isbn12 + checkDigit(isbn12).get
  }
  
  /**
   * Given a possible ISBN, verifies that it's valid and 
   * returns the 13-digit equivalent. If the original ISBN
   * is not valid, returns None. Any dashes that the user may
   * have entered are removed.
   */
  def asValidIsbn13(text: String): Option[String] = {
    def verify(possIsbn: String): Option[String] = {
      val noCheck = possIsbn.substring(0, possIsbn.length - 1)
      val check = checkDigit(noCheck)
      check match {
        case Some(cd) => if (possIsbn == noCheck + cd) Some(possIsbn) else None
        case _ => None
      }
    }
    val isbn = "-".r.replaceAllIn(text, "")
    isbn.length match {
      case 10 => verify(isbn).map(makeIsbn13(_))
      case 13 => verify(isbn)
      case _ => None
    }
  }
  
  object TitleForm extends Form {
    val isbn = new TextField("ISBN") {
      override val minLength = Some(10)
      override val maxLength = Some(13)
      override def validators = super.validators ++ List(Validator((str: String) => asValidIsbn13(str) match {
        case None => ValidationError("This value must be a valid 10 or 13-digit ISBN.")
	    case Some(isbn) => ValidationError(Nil)
    }), Validator((str: String) => Title.getByIsbn(str) match {
        case Some(isbn) => ValidationError("ISBN already exists in database.")
        case None => ValidationError(Nil)}))
    }
    val name = new TextField("Name") { override val maxLength = Some(80) }
    val author = new TextFieldOptional("Author(s)") { override val maxLength = Some(80) }
    val publisher = new TextFieldOptional("Publisher") { override val maxLength = Some(80) }
    val numPages = new NumericFieldOptional[Int]("Number Of Pages")
    val dimensions = new TextFieldOptional("Dimensions (in)")
    val weight = new NumericFieldOptional[Double]("Weight (lbs)")
    val imageUrl = new UrlFieldOptional("Image URL")
    
    val fields = List(isbn, name, author, publisher, numPages, dimensions, weight, imageUrl)
  }
  
  def addTitle = DbAction { implicit request =>
    if (request.method == "GET") Ok(views.html.books.addTitle(Binding(TitleForm)))
    else {
      Binding(TitleForm, request) match {
        case ib: InvalidBinding => Ok(views.html.books.addTitle(ib))
        case vb: ValidBinding => {
          val t = new Title (vb.valueOf(TitleForm.name), vb.valueOf(TitleForm.author), 
          vb.valueOf(TitleForm.publisher), vb.valueOf(TitleForm.isbn), vb.valueOf(TitleForm.numPages), 
          vb.valueOf(TitleForm.dimensions), vb.valueOf(TitleForm.weight), true, 
          new java.sql.Date(new java.util.Date().getTime()), Some("public/images/books/" + vb.valueOf(TitleForm.isbn)+ ".jpg"))
        request.pm.makePersistent(t)

        vb.valueOf(TitleForm.imageUrl) match {
          case Some(url) => try {
            downloadImage(url, vb.valueOf(TitleForm.isbn))
            Redirect(routes.Books.addTitle()).flashing("message" -> "Title added successfully")
          } catch {
            case e: Exception => Redirect(routes.Books.addTitle()).flashing("warn" -> "Image not downloaded. Update the title's image to try downloading again")
          }
          case None => Redirect(routes.Books.addTitle()).flashing("message" -> "Title added without an image")
        }
      }
    }
  }
}

  def downloadImage(url: java.net.URL, isbn: String) = {
    val pic = ImageIO.read(url)
    ImageIO.write(pic, "jpg", new File("public/images/books/" + isbn + ".jpg"))
  }

  def confirmation() = TODO
  
  def verifyTitle(isbnNum: Long) = TODO
  
  object AddPurchaseGroupForm extends Form {
    val isbn = new TextField("isbn") {
      override val minLength = Some(10)
      override val maxLength = Some(13)
      override def validators = super.validators ++ List(Validator((str: String) => asValidIsbn13(str) match {
          case None => ValidationError("This value must be a valid 10 or 13-digit ISBN.")
          case Some(isbn) => ValidationError(Nil)
        }))
      }
    val purchaseDate = new DateField("Purchase Date")
    val price = new NumericField[Double]("Price")
    val numCopies = new NumericField[Int]("Number of Copies")

    val fields = List(isbn, purchaseDate, price, numCopies)
    }

  def addPurchaseGroup = DbAction { implicit request =>
    if (request.method == "GET") Ok(views.html.books.addPurchaseGroup(Binding(AddPurchaseGroupForm)))
      else {
      implicit val pm = request.pm
      Binding(AddPurchaseGroupForm, request) match {
        case ib: InvalidBinding => Ok(views.html.books.addPurchaseGroup(ib))
        case vb: ValidBinding => {
        Title.getByIsbn(vb.valueOf(AddPurchaseGroupForm.isbn)) match {
          case None => Redirect(routes.Books.addPurchaseGroup()).flashing("error" -> "Title with the given ISBN not found")
          // TODO - Ask if the user would like to add the title if it is not found
          case Some(t) => {
            val p = new PurchaseGroup(t, vb.valueOf(AddPurchaseGroupForm.purchaseDate), vb.valueOf(AddPurchaseGroupForm.price))
            request.pm.makePersistent(p)

            // Next Copy Number
            val cand = QCopy.candidate
            val pCand = QPurchaseGroup.variable("pCand")
            val currentCopies = pm.query[Copy].filter(cand.purchaseGroup.eq(pCand).and(pCand.title.eq(t))).executeList()
            val newStart = currentCopies.length match {
              case 0 => 1
              case _ => {
                val maxCopy = currentCopies.sortWith((c1, c2) => c1.number < c2.number).last.number
                maxCopy + 1
              }
            }

            def addCopies(copyNumber: Int, copyNumberEnd: Int, purchaseGroup: PurchaseGroup): Unit = {
              if (copyNumber == copyNumberEnd) {
                val cpy = new Copy(purchaseGroup, copyNumber, false)
                request.pm.makePersistent(cpy)
              } else {
                val cpy = new Copy(purchaseGroup, copyNumber, false)
                request.pm.makePersistent(cpy)
                addCopies(copyNumber + 1, copyNumberEnd, purchaseGroup)
              }
            }

            // Add New Copies
            val copyNumberEnd = newStart + vb.valueOf(AddPurchaseGroupForm.numCopies) - 1
            addCopies(newStart, copyNumberEnd, p)
            val addedCopiesString = "copies " + newStart + " through " + copyNumberEnd + " added."

            val msg = "Purchase Group successfully added for: " + t.name + ". With " + addedCopiesString
            Redirect(routes.Books.addPurchaseGroup()).flashing("message" -> msg)
          }
        }
      }
    }
  }
}

  object CheckoutForm extends Form {
    val barcode = new TextField("Barcode") {
      override val minLength = Some(21)
      override val maxLength = Some(23)
    }
    val student = new TextField("Student")
    
    val fields = List(barcode, student)
  }

  def checkout = DbAction { implicit request =>
    if (request.method == "GET") Ok(views.html.books.checkout(Binding(CheckoutForm)))
      else {
      implicit val pm = request.pm
      Binding(CheckoutForm, request) match {
        case ib: InvalidBinding => Ok(views.html.books.checkout(ib))
        case vb: ValidBinding => {
          val student = Student.getByStateId(vb.valueOf(CheckoutForm.student))
          val copy = Copy.getByBarcode(vb.valueOf(CheckoutForm.barcode))
          student match {
            case None => Redirect(routes.Books.checkout()).flashing("error" -> "No such student.")
            case Some(stu) => {
              copy match {
                case None => Redirect(routes.Books.checkout()).flashing("error" -> "No copy with that barcode.")
                case Some(cpy) => {
                  if (cpy.isCheckedOut) {
                    Redirect(routes.Books.checkout()).flashing("error" -> "Copy already checked out")
                  } else {
                    val c = new Checkout(stu, cpy, new java.sql.Date(new java.util.Date().getTime()), null)
                    request.pm.makePersistent(c)
                    Redirect(routes.Books.checkout()).flashing("message" -> "Copy successfully checked out.")
                  }
                }
              }
            }
          }
      }
    }
  }
}

  object CheckoutBulkForm extends Form {
    val student = new TextField("Student")

    val fields = List(student)
  }

  def checkoutBulk() = DbAction { implicit request =>
    if (request.method == "GET") {
      Ok(html.books.checkoutBulk(Binding(CheckoutBulkForm)))
    } else {
      implicit val pm = request.pm
      Binding(CheckoutBulkForm, request) match {
        case ib: InvalidBinding => Ok(html.books.checkoutBulk(ib))
        case vb: ValidBinding => {
          val checkoutStu: String = vb.valueOf(CheckoutBulkForm.student)
          Student.getByStateId(checkoutStu) match {
            case None => Redirect(routes.Books.checkoutBulk).flashing("error" -> "Student not found.")
            case Some(s) => Redirect(routes.Books.checkoutBulkHelper(checkoutStu))
          }
        }
      }
    }
  }

  object CheckoutBulkHelperForm extends Form {
    val barcode = new TextField("Barcode") {
      override val minLength = Some(21)
      override val maxLength = Some(23)
    }

    val fields = List(barcode)
  }

  def checkoutBulkHelper(stu: String) = DbAction { implicit request =>
    implicit val pm = request.pm
    val dName = Student.getByStateId(stu) match {
        case None => "Unknown"
        case Some(s) => s.displayName
      }
    if (request.method == "GET") {
      val copies = request.visit.getAs[Vector[String]]("checkoutList").getOrElse(Vector[String]())
      val ct = copies.map(c => (c, Copy.getByBarcode(c).get.purchaseGroup.title.isbn))
      val zipped = ct.zipWithIndex
      Ok(html.books.checkoutBulkHelper(Binding(CheckoutBulkHelperForm), dName, zipped, stu))
    } else {
      val copies = request.visit.getAs[Vector[String]]("checkoutList").getOrElse(Vector[String]())
      val ct = copies.map(c => (c, Copy.getByBarcode(c).get.purchaseGroup.title.isbn))
      val zipped = ct.zipWithIndex
      Binding(CheckoutBulkHelperForm, request) match {
        case ib: InvalidBinding => Ok(html.books.checkoutBulkHelper(ib, dName, zipped, stu))
        case vb: ValidBinding => {
          Copy.getByBarcode(vb.valueOf(CheckoutBulkHelperForm.barcode)) match {
            case None => Redirect(routes.Books.checkoutBulkHelper(stu)).flashing("error" -> "Copy not found.")
            case Some(cpy) => {
              if (cpy.isCheckedOut) {
                Redirect(routes.Books.checkoutBulkHelper(stu)).flashing("error" -> "Copy already checked out.")
              } else {
                if (request.visit.getAs[Vector[String]]("checkoutList").getOrElse(Vector[String]()).exists(c => c == cpy.getBarcode)) {
                  Redirect(routes.Books.checkoutBulkHelper(stu)).flashing("error" -> "Copy already in queue.")
                } else {
                  request.visit.set("checkoutList", Vector[String](cpy.getBarcode()) ++ request.visit.getAs[Vector[String]]("checkoutList").getOrElse(Vector[String]()))
                  Redirect(routes.Books.checkoutBulkHelper(stu))
                }
              }
            }
          }
        }
      }
    }
  }

  def removeCopyFromList(stu: String, barcode: String) = DbAction { implicit request =>
    implicit val pm = request.pm

    val copies = request.visit.getAs[Vector[String]]("checkoutList").getOrElse(Vector[String]())
    val newCopies = copies.filter(_ != barcode)
    request.visit.set("checkoutList", newCopies)
    Redirect(routes.Books.checkoutBulkHelper(stu))
  }

  def removeAllCopiesFromList(stu: String) = DbAction { implicit request =>
    implicit val pm = request.pm

    request.visit.set("checkoutList", Vector[String]())
    Redirect(routes.Books.checkoutBulkHelper(stu))
  }

  def cancelBulkCheckout() = DbAction { implicit request =>
    implicit val pm = request.pm

    request.visit.set("checkoutList", Vector[String]())
    Redirect(routes.Books.checkoutBulk())
  }

  def checkoutBulkSubmit(stu: String) = DbAction { implicit request =>
    implicit val pm = request.pm
    val copies: Vector[String] = request.visit.getAs[Vector[String]]("checkoutList").getOrElse(Vector[String]())
    val checkedOutCopies: Vector[String] = copies.filter(c => Copy.getByBarcode(c).get.isCheckedOut)

    if (checkedOutCopies.isEmpty) {
      copies.foreach(c => request.pm.makePersistent(new Checkout(Student.getByStateId(stu).get, Copy.getByBarcode(c).get, new java.sql.Date(new java.util.Date().getTime()), null)))
      val mes = copies.length + " copie(s) successfully checked out to " + Student.getByStateId(stu).get.displayName
      request.visit.set("checkoutList", Vector[String]())
      Redirect(routes.Books.checkoutBulk()).flashing("message" -> mes)
    } else {
      val mes = "Books with the following barcodes already checked out: " + checkedOutCopies.toString.substring(7, checkedOutCopies.toString.length - 1)
      Redirect(routes.Books.checkoutBulkHelper(stu)).flashing("error" -> mes)
    }
  }

  object CheckInForm extends Form {
    val barcode = new TextField("Barcode") {
      override val minLength = Some(21)
      override val maxLength = Some(23)
    }

    val fields = List(barcode)
  }

  def checkIn = DbAction { implicit request =>
    if (request.method == "GET") Ok(views.html.books.checkIn(Binding(CheckInForm)))
      else {
      implicit val pm = request.pm
      Binding(CheckInForm, request) match {
        case ib: InvalidBinding => Ok(views.html.books.checkIn(ib))
        case vb: ValidBinding => {
          val cand = QCheckout.candidate
          Copy.getByBarcode(vb.valueOf(CheckInForm.barcode)) match {
            case None => Redirect(routes.Books.checkIn()).flashing("error" -> "No copy with the given barcode")
            case Some(cpy) => {
              pm.query[Checkout].filter(cand.endDate.eq(null.asInstanceOf[java.sql.Date]).and(cand.copy.eq(cpy))).executeOption() match {
                case None => Redirect(routes.Books.checkIn()).flashing("error" -> "Copy not checked out")
                case Some(currentCheckout) => {
                  currentCheckout.endDate = new java.sql.Date(new java.util.Date().getTime())
                  request.pm.makePersistent(currentCheckout)
                  Redirect(routes.Books.checkIn()).flashing("message" -> "Copy successfully checked in.")
                }
              }
          }
        }
        }
      }
    }
  }
  
  def lookup() = TODO
  
  def inspect() = TODO

  object ChooseCopyForm extends Form {
    val barcode = new TextField("Barcode") {
      override val minLength = Some(21)
      override val maxLength = Some(23)
      override def validators = super.validators ++ List(Validator((str: String) => Copy.getByBarcode(str) match {
          case None => ValidationError("Copy not found.")
          case Some(barcode) => ValidationError(Nil)
        }))
    }

    def fields = List(barcode)
  }

  def findCopyHistory() = DbAction { implicit req =>
    if (req.method == "GET") {
      Ok(html.books.findCopyHistory(Binding(ChooseCopyForm)))
    } else {
      Binding(ChooseCopyForm, req) match {
        case ib: InvalidBinding => Ok(html.books.findCopyHistory(ib))
        case vb: ValidBinding => {
          val lookupCopyBarcode: String = vb.valueOf(ChooseCopyForm.barcode)
          Redirect(routes.Books.copyHistory(lookupCopyBarcode))
        }
      }
    }
  }
  
  def copyHistory(barcode: String) = DbAction { implicit req =>
    implicit val pm = req.pm
    val df = new java.text.SimpleDateFormat("MM/dd/yyyy")
    Copy.getByBarcode(barcode) match {
      case None => NotFound("No copy with the given barcode.")
      case Some(copy) => {
        val header = "Copy #%d of %s".format(copy.number, copy.purchaseGroup.title.name)
        val coCand = QCheckout.candidate
        val rows: List[(String, String, String)] = pm.query[Checkout].filter(coCand.copy.eq(copy)).executeList().map(co => {
          (co.student.formalName, df.format(co.startDate), if (co.endDate == null) "" else df.format(co.endDate))
        })
        Ok(views.html.books.copyHistory(header, rows))
      }
    }
  }
  
  def confirmCopyLost(copyId: Long) = TODO
  
  def checkInLostCopy() = TODO
  
  def checkoutHistory(stateId: String) = DbAction { implicit req =>
  implicit val pm = req.pm
  val df = new java.text.SimpleDateFormat("MM/dd/yyyy")

  Student.getByStateId(stateId) match {
    case None => NotFound("Student not found.")
    case Some(currentStudent) => {
      val checkoutCand = QCheckout.candidate
      val currentBooks = pm.query[Checkout].filter(checkoutCand.student.eq(currentStudent)).executeList()
      val studentName = currentStudent.displayName
      val header = "Student: %s".format(studentName)
      val rows: List[(String, String, String)] = currentBooks.map(co => { (co.copy.purchaseGroup.title.name, df.format(co.startDate),
        if (co.endDate == null) "" else df.format(co.endDate))})
      Ok(views.html.books.checkoutHistory(header,rows))
    }
  }
}

  def findCheckoutHistory() = DbAction { implicit req =>
    implicit val pm = req.pm
    object ChooseStudentForm extends Form {
      val student = new TextField("Student") {
          override def validators = super.validators ++ List(Validator((str: String) => Student.getByStateId(str) match {
            case None => ValidationError("Student not found.")
            case Some(student) => ValidationError(Nil)
          }))
      }

    def fields = List(student)
  }
  if (req.method == "GET") {
      Ok(html.books.findCheckoutHistory(Binding(ChooseStudentForm)))
    } else {
      Binding(ChooseStudentForm, req) match {
        case ib: InvalidBinding => Ok(html.books.findCheckoutHistory(ib))
        case vb: ValidBinding => {
          val lookupStudentId: String = vb.valueOf(ChooseStudentForm.student)
          Redirect(routes.Books.checkoutHistory(lookupStudentId))
        }
      }
    }
  }

  def findCurrentCheckouts() = DbAction { implicit req =>
    implicit val pm = req.pm
    object ChooseStudentForm extends Form {
      val stateId = new TextField("Student") {
        override def validators = super.validators ++ List(Validator((str: String) => Student.getByStateId(str) match {
          case None => ValidationError("Student not found.")
          case Some(student) => ValidationError(Nil)
        }))
      }

      def fields = List(stateId)
    }
    if (req.method == "GET") {
      Ok(html.books.findPerspectiveHistory(Binding(ChooseStudentForm)))
    } else {
      Binding(ChooseStudentForm, req) match {
        case ib: InvalidBinding => Ok(html.books.findPerspectiveHistory(ib))
        case vb: ValidBinding => {
          val lookupStudentId: String = vb.valueOf(ChooseStudentForm.stateId)
          Redirect(routes.Books.currentCheckouts(lookupStudentId))
        }
      }
    }
  }

  def currentCheckouts(stateId: String) = DbAction { implicit req =>
  implicit val pm = req.pm
  val df = new java.text.SimpleDateFormat("MM/dd/yyyy")

  Student.getByStateId(stateId) match {
    case None => NotFound("Student not found.")
    case Some(currentStudent) => {
      val checkoutCand = QCheckout.candidate
      val currentBooks = pm.query[Checkout].filter(checkoutCand.endDate.eq(null.asInstanceOf[java.sql.Date]).and(checkoutCand.student.eq(currentStudent))).executeList()
      val studentName = currentStudent.displayName
      val header = "Student: %s".format(studentName)
      val rows: List[(String, String)] = currentBooks.map(co => { (co.copy.purchaseGroup.title.name, df.format(co.startDate))})
      Ok(views.html.books.currentCheckouts(header,rows))
    }
  }
}

  def checkoutsByTeacherStudents() = TODO
  
  def statistics() = TODO
  
  def copyStatusByTitle(isbn: String) = DbAction { implicit req =>
    implicit val pm = req.pm

    Title.getByIsbn(isbn) match {
      case None => NotFound("Title not found.")
      case Some(t) => {
        val cand = QCopy.candidate
        val pCand = QPurchaseGroup.variable("pCand")
        val currentCopies = pm.query[Copy].filter(cand.purchaseGroup.eq(pCand).and(pCand.title.eq(t))).executeList().sortWith((c1, c2) => c1.number < c2.number)

        val header = "Copy Status for " + t.name
        val rows: List[(String, String, String, String)] = currentCopies.map(cp => { (cp.number.toString, cp.isCheckedOut.toString, cp.isLost.toString, cp.deleted.toString)})
        Ok(views.html.books.copyStatusByTitle(header, rows))
      }
    }
  }
  
  def findCopyStatusByTitle() = DbAction { implicit req =>
    object ChooseTitleForm extends Form {
      val isbn = new TextField("ISBN") {
        override val minLength = Some(10)
        override val maxLength = Some(13)
        override def validators = super.validators ++ List(Validator((str: String) => Title.getByIsbn(str) match {
          case None => ValidationError("Title not found.")
          case Some(isbn) => ValidationError(Nil)
        }))
      }

      def fields = List(isbn)
    }
    if (req.method == "GET") {
      Ok(html.books.findCopyStatusByTitle(Binding(ChooseTitleForm)))
    } else {
      Binding(ChooseTitleForm, req) match {
        case ib: InvalidBinding => Ok(html.books.findCopyStatusByTitle(ib))
        case vb: ValidBinding => {
          val lookupTitleIsbn: String = vb.valueOf(ChooseTitleForm.isbn)
          Redirect(routes.Books.copyStatusByTitle(lookupTitleIsbn))
        }
      }
    }
  }

  def allBooksOut(grade: Int) = DbAction { implicit req =>
    implicit val pm = req.pm
    val df = new java.text.SimpleDateFormat("MM/dd/yyyy")
    val stu = QStudent.variable("stu")
    val cand = QCheckout.candidate
    val currentBooksOut = pm.query[Checkout].filter(cand.endDate.eq(null.asInstanceOf[java.sql.Date]).and(cand.student.eq(stu)).and(stu.grade.eq(grade))).executeList()
    val header = "Current books out for grade " + grade
    val rows: List[(String, String, String)] = currentBooksOut.map(co => { (co.copy.purchaseGroup.title.name, df.format(co.startDate), co.student.formalName)})
    Ok(views.html.books.allBooksOut(header, rows))
  }

  def findAllBooksOut() = DbAction { implicit req =>
    object ChooseGradeForm extends Form {
      val grade = new ChoiceField[Int]("Grade", List("Freshman" -> 9, "Sophomore" -> 10, "Junior" -> 11, "Senior" -> 12))

      def fields = List(grade)
    }
    if (req.method == "GET") {
      Ok(html.books.findAllBooksOut(Binding(ChooseGradeForm)))
    } else {
      Binding(ChooseGradeForm, req) match {
        case ib: InvalidBinding => Ok(html.books.findAllBooksOut(ib))
        case vb: ValidBinding => {
          val lookupGrade: Int = vb.valueOf(ChooseGradeForm.grade)
          Redirect(routes.Books.allBooksOut(lookupGrade))
        }
      }
    }
  }

  def copyInfo(barcode: String) = DbAction { implicit req =>
    implicit val pm = req.pm

    Copy.getByBarcode(barcode) match {
      case None => NotFound("Copy not found.")
      case Some(cpy) => {
        val df = new java.text.SimpleDateFormat("MM/dd/yyyy")

        val lost = cpy.isLost
        val num = cpy.number

        val pGroup = cpy.purchaseGroup
        val pDate = pGroup.purchaseDate
        val price = pGroup.price

        val title = pGroup.title
        val name = title.name
        val author = title.author
        val publisher = title.publisher
        val isbn = title.isbn
        val pages = title.numPages
        val dim = title.dimensions
        val weight = title.weight

        val checkedOut = cpy.isCheckedOut

        val rows: List[(String, String)] = List(("Name:", name), ("Author:", author.getOrElse("Unknown")), ("Publisher:", publisher.getOrElse("Unknown")), ("ISBN:", isbn), ("Pages:", pages.getOrElse("Unknown").toString), 
          ("Dimensions (in):", dim.getOrElse("Unknown")), ("Weight (lbs):", weight.getOrElse("Unknown").toString), ("Purchase Date:", df.format(pDate)), ("Price:", price.toString), ("Lost:", lost.toString), 
          ("Copy Number:", num.toString), ("Checked Out:", checkedOut.toString))
        val header = "Copy info for " + barcode

        Ok(views.html.books.copyInfo(header, rows))
      }
    }
  }

  def findCopyInfo() = DbAction { implicit req =>
    object ChooseCopyForm extends Form {
      val barcode = new TextField("Barcode") {
        override val minLength = Some(21)
        override val maxLength = Some(23)
        override def validators = super.validators ++ List(Validator((str: String) => Copy.getByBarcode(str) match {
          case None => ValidationError("Copy not found.")
          case Some(barcode) => ValidationError(Nil)
        }))
      }

      def fields = List(barcode)
    }
    if (req.method == "GET") {
      Ok(html.books.findCopyInfo(Binding(ChooseCopyForm)))
    } else {
      Binding(ChooseCopyForm, req) match {
        case ib: InvalidBinding => Ok(html.books.findCopyInfo(ib))
        case vb: ValidBinding => {
          val lookupBarcode: String = vb.valueOf(ChooseCopyForm.barcode)
          Redirect(routes.Books.copyInfo(lookupBarcode))
        }
      }
    }
  }

  def inventory() = DbAction { implicit req =>
    implicit val pm = req.pm

    val titles = pm.query[Title].executeList.sortWith((c1, c2) => c1.name < c2.name)

    val rows: List[(String, String, String, String)] = titles.map(ti => { (ti.name, ti.howManyCopies().toString, ti.howManyCheckedOut().toString, (ti.howManyCopies() - ti.howManyCheckedOut()).toString)})
    Ok(views.html.books.inventory(rows))
  }

  class EditTitleForm(iName: String, iAuthor: Option[String], iPublisher: Option[String], iNumPages: Option[Int], iDimensions: Option[String], iWeight: Option[Double]) extends Form {
    val name = new TextField("Name") {
      override def initialVal = Some(iName)
      override val maxLength = Some(80)
    }
    val author = new TextFieldOptional("Author(s)") {
      override def initialVal = Some(iAuthor)
      override val maxLength = Some(80)
    }
    val publisher = new TextFieldOptional("Publisher") {
      override def initialVal = Some(iPublisher)
      override val maxLength = Some(80)
    }
    val numPages = new NumericFieldOptional[Int]("Number Of Pages") {
      override def initialVal = Some(iNumPages)
    }
    val dimensions = new TextFieldOptional("Dimensions (in)") {
      override def initialVal = Some(iDimensions)
    }
    val weight = new NumericFieldOptional[Double]("Weight (lbs)") {
      override def initialVal = Some(iWeight)
    }
    val imageUrl = new UrlFieldOptional("New Image URL")

    override def cancelTo = "/books/editTitle"

    def fields = List(name, author, publisher, numPages, dimensions, weight, imageUrl)
  }

  def editTitleHelper(isbn: String) = DbAction { implicit request =>
    implicit val pm = request.pm
    val title = Title.getByIsbn(isbn).get

    if (request.method == "GET") {
      Ok(html.books.editTitleHelper(Binding(new EditTitleForm(title.name, title.author, title.publisher, title.numPages, title.dimensions, title.weight))))
    } else {
      val f = new EditTitleForm(title.name, title.author, title.publisher, title.numPages, title.dimensions, title.weight)
      Binding(f, request) match {
        case ib: InvalidBinding => Ok(html.books.editTitleHelper(ib))
        case vb: ValidBinding => {
          title.name = vb.valueOf(f.name)
          title.author = vb.valueOf(f.author)
          title.publisher = vb.valueOf(f.publisher)
          title.numPages = vb.valueOf(f.numPages)
          title.dimensions = vb.valueOf(f.dimensions)
          title.weight = vb.valueOf(f.weight)
          title.lastModified = new java.sql.Date(new java.util.Date().getTime())
          request.pm.makePersistent(title)

          vb.valueOf(f.imageUrl) match {
            case Some(url) => try {
              downloadImage(url, isbn)
              Redirect(routes.Application.index()).flashing("message" -> "Title updated successfully")
            } catch {
              case e: Exception => Redirect(routes.Application.index()).flashing("error" -> "Image not downloaded. Edit the tite to try downloading again")
            }
            case None => Redirect(routes.Application.index()).flashing("message" -> "Title updated successfully")
          }
        }
      }
    }
  }

  object ChooseTitleForm extends Form {
    val isbn = new TextField("ISBN") {
      override val minLength = Some(10)
      override val maxLength = Some(13)
      override def validators = super.validators ++ List(Validator((str: String) => Title.getByIsbn(str) match {
          case None => ValidationError("Title with the given ISBN not found.")
          case Some(title) => ValidationError(Nil)
        }))
    }

    val fields = List(isbn)
  }

  def editTitle() = DbAction { implicit req =>
    implicit val pm = req.pm
    if (req.method == "GET") {
      Ok(html.books.editTitle(Binding(ChooseTitleForm)))
    } else {
      Binding(ChooseTitleForm, req) match {
        case ib: InvalidBinding => Ok(html.books.editTitle(ib))
        case vb: ValidBinding => {
          val lookupIsbn: String = vb.valueOf(ChooseTitleForm.isbn)
          Redirect(routes.Books.editTitleHelper(lookupIsbn))
        }
      }
    }
  }

  def makeBarcode(barcode: String): Barcode =  {
    val b: Barcode128 = new Barcode128()
    b.setCode(barcode)
    b.setAltText(barcode)
    return b
  }

  def cropText(s: String): String = {
    // This will crop strings so that they fit on a label
    val w = Utilities.inchesToPoints(2.6f) - 12
    val font = BaseFont.createFont(BaseFont.TIMES_ROMAN, BaseFont.CP1252, false)
    if (font.getWidthPoint(s, 10f) <= w) {
      s
    } else {
      cropText(s.substring(0, s.length - 1))
    }
  }

  def makePdf(barcodes: List[(Barcode, String, String, String)]) { //Barcode, title.name, title.author, title.publisher
    // Spacing in points
    // Bottom left: 0,0
    // Top right: 612, 792

    // Avery 5160 labels have 1/2 inch top/bottom margins and 0.18 inch left/right margins.
    // Labels are 2.6" by 1". Labels abut vertically but there is a .15" gutter horizontally.

    // A Barcode Label is an Avery 5160 label with three lines of text across the top and
    // a Code128 barcode under them. The text is cropped to an appropriate width and the
    // barcode is sized to fit within the remainder of the label.

    // inchesToPoints gives the point value for a measurement in inches

    // Spacing Increments
    // Top to bottom (inches)
    // 0.5 1.0 1.0 1.0 0.5
    // Left to right (inches)
    // 0.18 2.6 0.15 2.6 0.15 2.6 0.18

    val halfInch = Utilities.inchesToPoints(.5f)
    val inch = Utilities.inchesToPoints(1f)
    val gutter = Utilities.inchesToPoints(.15f)
    val lAndRBorder = Utilities.inchesToPoints(.18f)
    val labelWidth = Utilities.inchesToPoints(2.6f)

    val topLeftX = lAndRBorder
    val topLeftY = 792 - halfInch - 10

    val result: String = "public/printable.pdf"
    val document: Document = new Document(PageSize.LETTER)
    val writer = PdfWriter.getInstance(document, new FileOutputStream(result))
    document.open()
    val cb = writer.getDirectContent() //PdfContentByte
    val font = BaseFont.createFont(BaseFont.TIMES_ROMAN, BaseFont.CP1252, false)
    cb.setFontAndSize(font, 10)
    var labelTopLeftX = topLeftX
    var labelTopLeftY = topLeftY
    var n = 0

    for (barcode <- barcodes) {
      // Do this for each barcode but change the position so that it is a new label each time

      cb.showTextAligned(PdfContentByte.ALIGN_LEFT, cropText(barcode._2), (labelTopLeftX + 6), labelTopLeftY, 0)
      cb.showTextAligned(PdfContentByte.ALIGN_LEFT, cropText(barcode._3), (labelTopLeftX + 6), (labelTopLeftY - 8), 0)
      cb.showTextAligned(PdfContentByte.ALIGN_LEFT, cropText(barcode._4), (labelTopLeftX + 6), (labelTopLeftY - 16), 0)
      val b = barcode._1
      b.setX(0.7f)
      val img = b.createImageWithBarcode(cb, null, null)
      val barcodeOffset = (labelWidth - img.getPlainWidth())/2
      cb.addImage(img, img.getPlainWidth, 0, 0, img.getPlainHeight, (labelTopLeftX + barcodeOffset), (labelTopLeftY - 52))

      n += 1

      if (n % 3 == 0) {
        labelTopLeftX = topLeftX
        labelTopLeftY = labelTopLeftY - inch
      } else {
        labelTopLeftX = labelTopLeftX + labelWidth + gutter
      }
      if (n % 30 == 0) {
        // Make a new page
        document.newPage()
        cb.setFontAndSize(font, 10)
        labelTopLeftY = topLeftY
      }
    }

    document.close()
  }

  val tempBarcode = makeBarcode("1234567890123-200-00001")

  val testBarcodes = List((tempBarcode, "123456788901234567890123456789012345678901234567890", "abcdefghijklmnopqrstuvwxyz123456789012345678901234567890", "qwertyuiopasdfghjklzxcvbnm098765432112345678901234567890"),
    (tempBarcode, "Name", "Author", "Publisher"), (tempBarcode, "Herp", "Derp", "Snerp"), (tempBarcode, "Welp", "Foo", "Bar"))

  val longTestBarcodes = testBarcodes ++ testBarcodes ++ testBarcodes ++ testBarcodes ++ testBarcodes ++ testBarcodes ++ testBarcodes ++ testBarcodes ++ testBarcodes

  def addTitleToPrintQueue(isbn: String, copyRange: String) = DbAction { implicit request =>
    implicit val pm = request.pm

    Title.getByIsbn(isbn) match {
      case None => Redirect(routes.Books.addTitleToPrintQueueHelper()).flashing("error" -> "Title not found")
      case Some(t) => {
        try {
          sanatizeCopyRange(copyRange)
          val l = new LabelQueueSet(request.visit.perspective.getOrElse(null), t, copyRange)
          request.pm.makePersistent(l)
          Redirect(routes.Books.addTitleToPrintQueueHelper()).flashing("message" -> "Labels added to print queue")

        } catch {
          case e: Exception => Redirect(routes.Books.addTitleToPrintQueueHelper()).flashing("error" -> "Invalid copy range")
        }
      }
    }
  }

  object AddTitleToPrintQueueForm extends Form {
    val isbn = new TextField("ISBN") {
      override val minLength = Some(10)
      override val maxLength = Some(13)
    }
    val copyRange = new TextField("Copy Range")

    val fields = List(isbn, copyRange)
  }

  def addTitleToPrintQueueHelper() = DbAction { implicit request =>
    implicit val pm = request.pm
    if (request.method == "GET") {
      Ok(html.books.addTitleToPrintQueueHelper(Binding(AddTitleToPrintQueueForm)))
    } else {
      Binding(AddTitleToPrintQueueForm, request) match {
        case ib: InvalidBinding => Ok(html.books.addTitleToPrintQueueHelper(ib))
        case vb: ValidBinding => {
          val lookupIsbn: String = vb.valueOf(AddTitleToPrintQueueForm.isbn)
          val copyRange: String = vb.valueOf(AddTitleToPrintQueueForm.copyRange)
          Redirect(routes.Books.addTitleToPrintQueue(lookupIsbn, copyRange))
        }
      }
    }
  }

  def viewPrintQueue() = DbAction { implicit request =>
    implicit val pm = request.pm

    val labelSets = pm.query[LabelQueueSet].executeList
    val rows: List[(String, String, String, Long)] = labelSets.map(ls => { (ls.title.name, ls.title.isbn, ls.copyRange, ls.id)})
    Ok(html.books.viewPrintQueue(rows))
  }

  def removeFromPrintQueue(id: Long) = DbAction { implicit request =>
    implicit val pm = request.pm

    LabelQueueSet.getById(id) match {
      case None => Redirect(routes.Books.viewPrintQueue()).flashing("error" -> "ID not found")
      case Some(l) => {
        pm.deletePersistent(l)
        Redirect(routes.Books.viewPrintQueue()).flashing("message" -> "Labels removed from print queue")
      }
    }
  }

  def print(l: List[LabelQueueSet]) {
    var printList = List[(Barcode, String, String, String)]()
    for (x <- l) {
      val r = sanatizeCopyRange(x.copyRange)
      for (y <- r) {
        val b = makeBarcode("%s-%s-%05d".format(x.title.isbn, "200", y))
        printList = printList :+ (b, x.title.name, x.title.author.getOrElse(""), x.title.publisher.getOrElse(""))
      }
    }
    makePdf(printList)
  }

  def sanatizeCopyRange(s: String): List[Int] = {
    val newS = s.trim.split(",")
    var res: List[Int] = List[Int]()
    for (x <- newS) {
      if (x.contains("-")) {
        val newX = x.trim.split("-")
        val startVal = newX(0).trim.toInt
        val endVal = newX(1).trim.toInt
        val temp = startVal.until(endVal + 1).toList
        res = res ++ temp
      } else {
        res = res :+ x.trim.toInt
      }
    }
    res
  }

  def printEntireQueue() = DbAction { implicit request =>
    implicit val pm = request.pm

    val labelQueueSets = pm.query[LabelQueueSet].executeList
    print(labelQueueSets)
    for (x <- labelQueueSets) {
      pm.deletePersistent(x)
    }
    Ok.sendFile(content = new java.io.File("public/printable.pdf"), inline = true)
  }

  def deleteCopy(barcode: String) = DbAction { implicit request =>
    implicit val pm = request.pm

    Copy.getByBarcode(barcode) match {
      case None => Redirect(routes.Books.deleteCopyHelper()).flashing("error" -> "Copy not found")
      case Some(c) => {
        val cand = QCheckout.candidate
        pm.query[Checkout].filter(cand.copy.eq(c).and(cand.endDate.eq(null.asInstanceOf[java.sql.Date]))).executeOption() match {
          case None => {
            c.deleted = true
            request.pm.makePersistent(c)
          }
          case Some(ch) => {
            ch.endDate = new java.sql.Date(new java.util.Date().getTime())
            request.pm.makePersistent(ch)
            c.deleted = true
            request.pm.makePersistent(c)
          }
        }
      }
      Redirect(routes.Books.deleteCopyHelper()).flashing("message" -> "Copy deleted")
    }
  }

  def deleteCopyHelper() = DbAction { implicit req =>
  implicit val pm = req.pm
  if (req.method == "GET") {
    Ok(html.books.deleteCopyHelper(Binding(ChooseCopyForm)))
  } else {
    Binding(ChooseCopyForm, req) match {
      case ib: InvalidBinding => Ok(html.books.deleteCopyHelper(ib))
      case vb: ValidBinding => {
        val lookupBarcode: String = vb.valueOf(ChooseCopyForm.barcode)
        Redirect(routes.Books.deleteCopy(lookupBarcode))
      }
    }
  }
}

  def deleteTitle(isbn: String) = DbAction { implicit request =>
    implicit val pm = request.pm

    Title.getByIsbn(isbn) match {
      case None => Redirect(routes.Books.deleteCopyHelper()).flashing("error" -> "Title not found") // TODO - Change this to the right place
      case Some(t) => {
        val cand = QPurchaseGroup.candidate
        val pg = pm.query[PurchaseGroup].filter(cand.title.eq(t)).executeList()
        if (pg.isEmpty) {
          request.pm.deletePersistent(t)
          Redirect(routes.Books.deleteCopyHelper()).flashing("message" -> "Title successfully deleted.") // TODO - Change this to the right place
        } else {
          Redirect(routes.Books.deleteCopyHelper()).flashing("error" -> "Books of this title purchased. Contact your system administrator to remove.") // TODO -ditto
        }
      }
    }
  }

  def deleteTitleHelper() = DbAction { implicit req =>
  implicit val pm = req.pm
  if (req.method == "GET") {
    Ok(html.books.deleteTitleHelper(Binding(ChooseTitleForm)))
  } else {
    Binding(ChooseTitleForm, req) match {
      case ib: InvalidBinding => Ok(html.books.deleteTitleHelper(ib))
      case vb: ValidBinding => {
        val lookupIsbn: String = vb.valueOf(ChooseTitleForm.isbn)
        Redirect(routes.Books.deleteTitle(lookupIsbn))
      }
    }
  }
}

}
