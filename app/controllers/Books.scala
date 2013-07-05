package controllers

import java.io.{ File, FileInputStream, FileOutputStream }
import javax.imageio.ImageIO
import play.api.mvc.Controller
import org.datanucleus.api.jdo.query._
import org.datanucleus.query.typesafe._
import com.itextpdf.text.pdf.{ Barcode128, Barcode, PdfContentByte, PdfWriter, BaseFont }
import com.itextpdf.text.{ BaseColor, Document, DocumentException, PageSize, Paragraph, Utilities }
import scalajdo.DataStore
import models.books._
import models.users._
import forms.fields._
import forms.validators.Validator
import forms.validators.ValidationError
import forms.{ Binding, InvalidBinding, ValidBinding, Call, Method, Form }
import controllers.users.VisitAction
import config.Config
import com.google.inject.{ Inject, Singleton }
import org.joda.time.LocalDateTime
import org.joda.time.LocalDate

@Singleton
class Books @Inject()(implicit config: Config) extends Controller {
  object TitleForm extends Form {
    val isbn = new TextField("ISBN") {
      override val minLength = Some(10)
      override val maxLength = Some(13)
      override def validators = super.validators ++ List(Validator((str: String) => Title.asValidIsbn13(str) match {
        case None => ValidationError("This value must be a valid 10 or 13-digit ISBN.")
        case Some(isbn) => ValidationError(Nil)
      }), Validator((str: String) => Title.getByIsbn(str) match {
        case Some(isbn) => ValidationError("ISBN already exists in database.")
        case None => ValidationError(Nil)
      }))
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

  /**
   * Regex: /books/addTitle
   *
   * A form that allows users to add information for a new book to the database.
   */
  def addTitle() = VisitAction { implicit request =>
    Ok(views.html.books.addTitle(Binding(TitleForm)))
  }

  def addTitleP() = VisitAction { implicit request =>
    Binding(TitleForm, request) match {
      case ib: InvalidBinding => Ok(views.html.books.addTitle(ib))
      case vb: ValidBinding => DataStore.execute { implicit pm =>
        val t = new Title(vb.valueOf(TitleForm.name), vb.valueOf(TitleForm.author),
          vb.valueOf(TitleForm.publisher), vb.valueOf(TitleForm.isbn), vb.valueOf(TitleForm.numPages),
          vb.valueOf(TitleForm.dimensions), vb.valueOf(TitleForm.weight), true,
          Some(LocalDateTime.now()), Some("public/images/books/" + vb.valueOf(TitleForm.isbn) + ".jpg"))
        pm.makePersistent(t)

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

  // Helper Method
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
      override def validators = super.validators ++ List(Validator((str: String) => Title.asValidIsbn13(str) match {
        case None => ValidationError("This value must be a valid 10 or 13-digit ISBN.")
        case Some(isbn) => ValidationError(Nil)
      }))
    }
    val purchaseDate = new DateField("Purchase Date")
    val price = new NumericField[Double]("Price")
    val numCopies = new NumericField[Int]("Number of Copies")

    val fields = List(isbn, purchaseDate, price, numCopies)
  }

  /**
   * Regex: /books/addPurchaseGroup
   *
   * A form that allows users to add a purchase of a certain title, and update
   * information about the number of copies of the book.
   */
  def addPurchaseGroup() = VisitAction { implicit request =>
    Ok(views.html.books.addPurchaseGroup(Binding(AddPurchaseGroupForm)))
  }

  def addPurchaseGroupP() = VisitAction { implicit request =>
    DataStore.execute { pm =>
      Binding(AddPurchaseGroupForm, request) match {
        case ib: InvalidBinding => Ok(views.html.books.addPurchaseGroup(ib))
        case vb: ValidBinding => {
          Title.getByIsbn(vb.valueOf(AddPurchaseGroupForm.isbn)) match {
            case None => Redirect(routes.Books.addPurchaseGroup()).flashing("error" -> "Title with the given ISBN not found")
            // TODO - Ask if the user would like to add the title if it is not found
            case Some(t) => {
              val p = new PurchaseGroup(t, vb.valueOf(AddPurchaseGroupForm.purchaseDate), vb.valueOf(AddPurchaseGroupForm.price))
              pm.makePersistent(p)

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
                  pm.makePersistent(cpy)
                } else {
                  val cpy = new Copy(purchaseGroup, copyNumber, false)
                  pm.makePersistent(cpy)
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

  /**
   * Regex: /books/checkout
   *
   * A form page that allows administrators to checkout a copy of a book to a student.
   */
  def checkout = VisitAction { implicit request =>
    Ok(views.html.books.checkout(Binding(CheckoutForm)))
  }

  def checkoutP() = VisitAction { implicit request =>
    Binding(CheckoutForm, request) match {
      case ib: InvalidBinding => Ok(views.html.books.checkout(ib))
      case vb: ValidBinding => DataStore.execute { implicit pm =>
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
                  val c = new Checkout(stu, cpy, Some(LocalDate.now()), None)
                  pm.makePersistent(c)
                  Redirect(routes.Books.checkout()).flashing("message" -> "Copy successfully checked out.")
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

  /**
   * Regex: /books/checkoutBulk
   *
   * Another form that allows books to be checked out in bulk.
   * Redirects to another page.
   */
  def checkoutBulk() = VisitAction { implicit request =>
    Ok(views.html.books.checkoutBulk(Binding(CheckoutBulkForm)))
  }

  def checkoutBulkP() = VisitAction { implicit request =>
    Binding(CheckoutBulkForm, request) match {
      case ib: InvalidBinding => Ok(views.html.books.checkoutBulk(ib))
      case vb: ValidBinding => {
        val checkoutStu: String = vb.valueOf(CheckoutBulkForm.student)
        Student.getByStateId(checkoutStu) match {
          case None => Redirect(routes.Books.checkoutBulk).flashing("error" -> "Student not found.")
          case Some(s) => Redirect(routes.Books.checkoutBulkHelper(checkoutStu))
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

  /**
   * Regex: /books/checkoutBulkHelper/:stu
   *
   * A form that helps /books/checkoutBulk.
   * A form that checkouts multiple copies that are parameters of the request
   * to a student with given id.
   */
  def checkoutBulkHelper(stu: String) = VisitAction { implicit request =>
    val dName = Student.getByStateId(stu) match {
      case None => "Unknown"
      case Some(s) => s.displayName
    }
    val visit = request.visit
    val copies = visit.getAs[Vector[String]]("checkoutList").getOrElse(Vector[String]())
    val ct = copies.map(c => (c, Copy.getByBarcode(c).get.purchaseGroup.title.isbn))
    val zipped = ct.zipWithIndex
    Ok(views.html.books.checkoutBulkHelper(Binding(CheckoutBulkHelperForm), dName, zipped, stu))
  }

  def checkoutBulkHelperP(stu: String) = VisitAction { implicit request =>
    val dName = Student.getByStateId(stu) match {
      case None => "Unknown"
      case Some(s) => s.displayName
    }
    val visit = request.visit
    val copies = visit.getAs[Vector[String]]("checkoutList").getOrElse(Vector[String]())
    val ct = copies.map(c => (c, Copy.getByBarcode(c).get.purchaseGroup.title.isbn))
    val zipped = ct.zipWithIndex
    Binding(CheckoutBulkHelperForm, request) match {
      case ib: InvalidBinding => Ok(views.html.books.checkoutBulkHelper(ib, dName, zipped, stu))
      case vb: ValidBinding => {
        Copy.getByBarcode(vb.valueOf(CheckoutBulkHelperForm.barcode)) match {
          case None => Redirect(routes.Books.checkoutBulkHelper(stu)).flashing("error" -> "Copy not found.")
          case Some(cpy) => {
            if (cpy.isCheckedOut) {
              Redirect(routes.Books.checkoutBulkHelper(stu)).flashing("error" -> "Copy already checked out.")
            } else {
              if (visit.getAs[Vector[String]]("checkoutList").getOrElse(Vector[String]()).exists(c => c == cpy.getBarcode)) {
                Redirect(routes.Books.checkoutBulkHelper(stu)).flashing("error" -> "Copy already in queue.")
              } else {
                visit.set("checkoutList", Vector[String](cpy.getBarcode()) ++ visit.getAs[Vector[String]]("checkoutList").getOrElse(Vector[String]()))
                Redirect(routes.Books.checkoutBulkHelper(stu))
              }
            }
          }
        }
      }
    }
  }

  /**
   * Regex: /books/removeCopyFromList/:stu/:bc
   *
   * Removes a copy with given barcode (bc) from the student's with given id (stu)
   * checkout list. Redirects back to the checkout helper.
   */
  def removeCopyFromList(stu: String, barcode: String) = VisitAction { implicit request =>
    val copies = request.visit.getAs[Vector[String]]("checkoutList").getOrElse(Vector[String]())
    val newCopies = copies.filter(_ != barcode)
    request.visit.set("checkoutList", newCopies)
    Redirect(routes.Books.checkoutBulkHelper(stu))
  }

  /**
   * Regex: /books/removeCopyFromList/:stu/:bc
   *
   * Removes all copies from the student's with given id (stu)
   * checkout list. Redirects back to the checkout helper.
   */
  def removeAllCopiesFromList(stu: String) = VisitAction { implicit request =>
    request.visit.set("checkoutList", Vector[String]())
    Redirect(routes.Books.checkoutBulkHelper(stu))
  }

  /**
   * Regex: /books/cancelBulkCheckout
   *
   * Sets the parameter of the request to empty and redirects
   * to the initial bulk checkout form.
   */
  def cancelBulkCheckout() = VisitAction { implicit request =>
    request.visit.set("checkoutList", Vector[String]())
    Redirect(routes.Books.checkoutBulk())
  }

  /**
   * Regex: /books/checkoutBulkSubmit/:stu
   *
   * Checks out all the books in the checkoutList request parameter to
   * the student with given id (stu)
   */
  def checkoutBulkSubmit(stu: String) = VisitAction { implicit request =>
    val copies: Vector[String] = request.visit.getAs[Vector[String]]("checkoutList").getOrElse(Vector[String]())
    val checkedOutCopies: Vector[String] = copies.filter(c => Copy.getByBarcode(c).get.isCheckedOut)

    if (checkedOutCopies.isEmpty) {
      copies.foreach(c => DataStore.pm.makePersistent(
          new Checkout(Student.getByStateId(stu).get, Copy.getByBarcode(c).get, Some(LocalDate.now()), None)))
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

  /**
   * Regex: /books/checkIn
   *
   * A form that allows for a book to be checked back in.
   */
  def checkIn() = VisitAction { implicit request =>
    Ok(views.html.books.checkIn(Binding(CheckInForm)))
  }

  def checkInP() = VisitAction { implicit request =>
    Binding(CheckInForm, request) match {
      case ib: InvalidBinding => Ok(views.html.books.checkIn(ib))
      case vb: ValidBinding => DataStore.execute { pm =>
        val cand = QCheckout.candidate
        Copy.getByBarcode(vb.valueOf(CheckInForm.barcode)) match {
          case None => Redirect(routes.Books.checkIn()).flashing("error" -> "No copy with the given barcode")
          case Some(cpy) => {
            pm.query[Checkout].filter(cand.endDate.eq(null.asInstanceOf[java.sql.Date]).and(cand.copy.eq(cpy))).executeOption() match {
              case None => Redirect(routes.Books.checkIn()).flashing("error" -> "Copy not checked out")
              case Some(currentCheckout) => {
                currentCheckout.endDate = LocalDate.now()
                pm.makePersistent(currentCheckout)
                Redirect(routes.Books.checkIn()).flashing("message" -> "Copy successfully checked in.")
              }
            }
          }
        }
      }
    }
  }

  def lookup() = TODO

  def inspect() = TODO

  /**
   * Regex: /books/findCopyHistory
   *
   * A form that allows the user to find the information on a copy with a given barcode.
   * The post request redirects to /books/copyHistory/:barcode
   */
  def findCopyHistory() = VisitAction { implicit req =>
    Ok(views.html.books.findCopyHistory(Binding(ChooseCopyForm)))
  }

  def findCopyHistoryP() = VisitAction { implicit req =>
    Binding(ChooseCopyForm, req) match {
      case ib: InvalidBinding => Ok(views.html.books.findCopyHistory(ib))
      case vb: ValidBinding => {
        val lookupCopyBarcode: String = vb.valueOf(ChooseCopyForm.barcode)
        Redirect(routes.Books.copyHistory(lookupCopyBarcode))
      }
    }
  }

  /**
   * Regex: /books/copyHistory/:barcode
   *
   * A page that displays information about the history of the copy
   * with the given barcode.
   */
  def copyHistory(barcode: String) = VisitAction { implicit req =>
    val df = new java.text.SimpleDateFormat("MM/dd/yyyy")
    Copy.getByBarcode(barcode) match {
      case None => NotFound("No copy with the given barcode.")
      case Some(copy) => DataStore.execute { pm =>
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

  /**
   * Regex: /books/checkoutHistory/:studentId
   *
   * Displays information about the books checkedout to a student
   * with the given id (studentId).
   */
  def checkoutHistory(stateId: String) = VisitAction { implicit req =>
    val df = new java.text.SimpleDateFormat("MM/dd/yyyy")

    Student.getByStateId(stateId) match {
      case None => NotFound("No student with the given id.")
      case Some(currentStudent) => DataStore.execute { implicit pm =>
        val checkoutCand = QCheckout.candidate
        val currentBooks = pm.query[Checkout].filter(checkoutCand.student.eq(currentStudent)).executeList()
        val studentName = currentStudent.displayName
        val header = "Student: %s".format(studentName)
        val rows: List[(String, String, String)] = currentBooks.map(co => {
          (co.copy.purchaseGroup.title.name, df.format(co.startDate),
            if (co.endDate == null) "" else df.format(co.endDate))
        })
        Ok(views.html.books.checkoutHistory(header, rows))
      }
    }
  }

  object ChooseStudentForm extends Form {
    val stateId = new TextField("Student") {
      override def validators = super.validators ++ List(Validator((str: String) => Student.getByStateId(str) match {
        case None => ValidationError("Student not found.")
        case Some(student) => ValidationError(Nil)
      }))
    }

    def fields = List(stateId)
  }

  /**
   * Regex: /books/findCheckoutHistory
   *
   * A form page that allows the user to find the checkout history
   * for a desired student.
   */
  def findCheckoutHistory() = VisitAction { implicit req =>
    Ok(views.html.books.findCheckoutHistory(Binding(ChooseStudentForm)))
  }

  def findCheckoutHistoryP() = VisitAction { implicit req =>
    Binding(ChooseStudentForm, req) match {
      case ib: InvalidBinding => Ok(views.html.books.findCheckoutHistory(ib))
      case vb: ValidBinding => DataStore.execute { implicit pm =>
        val lookupStudentId: String = vb.valueOf(ChooseStudentForm.stateId)
        Redirect(routes.Books.checkoutHistory(lookupStudentId))
      }
    }
  }

  /**
   * Regex: /books/findCurrentCheckouts
   *
   * A form page that allows users to find books currently
   * checked out to a student they provide.
   */
  def findCurrentCheckouts() = VisitAction { implicit req =>
    Ok(views.html.books.findRoleHistory(Binding(ChooseStudentForm)))
  }

  def findCurrentCheckoutsP() = VisitAction { implicit req =>
    Binding(ChooseStudentForm, req) match {
      case ib: InvalidBinding => Ok(views.html.books.findRoleHistory(ib))
      case vb: ValidBinding => {
        val lookupStudentId: String = vb.valueOf(ChooseStudentForm.stateId)
        Redirect(routes.Books.currentCheckouts(lookupStudentId))
      }
    }
  }

  /**
   * Regex: /books/currentCheckouts/:studentId
   *
   * Displays information about the books currently check out
   * to a student with the given id (studentId).
   */
  def currentCheckouts(stateId: String) = VisitAction { implicit req =>
    val df = new java.text.SimpleDateFormat("MM/dd/yyyy")

    Student.getByStateId(stateId) match {
      case None => NotFound("No student with the given id")
      case Some(currentStudent) => DataStore.execute { implicit pm =>
        val checkoutCand = QCheckout.candidate
        val currentBooks = pm.query[Checkout].filter(checkoutCand.endDate.eq(null.asInstanceOf[java.sql.Date]).and(checkoutCand.student.eq(currentStudent))).executeList()
        val studentName = currentStudent.displayName
        val header = "Student: %s".format(studentName)
        val rows: List[(String, String)] = currentBooks.map(co => { (co.copy.purchaseGroup.title.name, df.format(co.startDate)) })
        Ok(views.html.books.currentCheckouts(header, rows))
      }
    }
  }

  def checkoutsByTeacherStudents() = TODO

  def statistics() = TODO

  /**
   * Regex: /books/copyStatusByTitle/:isbn
   *
   * Displays information about the status of a copy with given isbn.
   */
  def copyStatusByTitle(isbn: String) = VisitAction { implicit req =>
    Title.getByIsbn(isbn) match {
      case None => NotFound("Title not found.")
      case Some(t) => {
        val cand = QCopy.candidate
        val pCand = QPurchaseGroup.variable("pCand")
        val currentCopies = DataStore.pm.query[Copy].filter(cand.purchaseGroup.eq(pCand).and(pCand.title.eq(t))).executeList().sortWith((c1, c2) => c1.number < c2.number)

        val header = "Copy Status for " + t.name
        val rows: List[(String, String, String, String)] = currentCopies.map(cp => { (cp.number.toString, cp.isCheckedOut.toString, cp.isLost.toString, cp.deleted.toString) })
        Ok(views.html.books.copyStatusByTitle(header, rows))
      }
    }
  }

  /**
   * Regex: /books/findCopyStatusByTitle
   *
   * A form page that allows a user to find a copy by its isbn.
   */
  def findCopyStatusByTitle() = VisitAction { implicit req =>
    Ok(views.html.books.findCopyStatusByTitle(Binding(ChooseTitleForm)))
  }

  def findCopyStatusByTitleP() = VisitAction { implicit req =>
    Binding(ChooseTitleForm, req) match {
      case ib: InvalidBinding => Ok(views.html.books.findCopyStatusByTitle(ib))
      case vb: ValidBinding => {
        val lookupTitleIsbn: String = vb.valueOf(ChooseTitleForm.isbn)
        Redirect(routes.Books.copyStatusByTitle(lookupTitleIsbn))
      }
    }
  }

  /**
   * Regex: /books/allBooksOut/:grade
   *
   * Displays all of the books checked out for a given grade.
   */
  def allBooksOut(grade: Int) = VisitAction { implicit req =>
    DataStore.execute { implicit pm =>
      val df = new java.text.SimpleDateFormat("MM/dd/yyyy")
      val stu = QStudent.variable("stu")
      val cand = QCheckout.candidate
      val currentBooksOut = pm.query[Checkout].filter(cand.endDate.eq(null.asInstanceOf[java.sql.Date]).and(cand.student.eq(stu)).and(stu.grade.eq(grade))).executeList()
      val header = "Current books out for grade " + grade
      val rows: List[(String, String, String)] = currentBooksOut.map(co => { (co.copy.purchaseGroup.title.name, df.format(co.startDate), co.student.formalName) })
      Ok(views.html.books.allBooksOut(header, rows))
    }
  }

  object ChooseGradeForm extends Form {
    val grade = new ChoiceField[Int]("Grade", List("Freshman" -> 9, "Sophomore" -> 10, "Junior" -> 11, "Senior" -> 12))

    def fields = List(grade)
  }

  /**
   * Regex: /books/findAllBooksOut
   *
   * A form page that allows a user to find al the books out for a page.
   */
  def findAllBooksOut() = VisitAction { implicit req =>
    Ok(views.html.books.findAllBooksOut(Binding(ChooseGradeForm)))
  }

  def findAllBooksOutP() = VisitAction { implicit req =>
    Binding(ChooseGradeForm, req) match {
      case ib: InvalidBinding => Ok(views.html.books.findAllBooksOut(ib))
      case vb: ValidBinding => DataStore.execute { implicit pm =>
        val lookupGrade: Int = vb.valueOf(ChooseGradeForm.grade)
        Redirect(routes.Books.allBooksOut(lookupGrade))
      }
    }
  }

  /**
   * Regex: /books/copyInfo/:barcode
   *
   * Displays information on a copy with given barcode.
   */
  def copyInfo(barcode: String) = VisitAction { implicit req =>
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

  /**
   * Regex: /books/findCopyInfo
   *
   * A form page that allows users to find info on a copy with a certain barcode.
   */
  def findCopyInfo() = VisitAction { implicit req =>
    Ok(views.html.books.findCopyInfo(Binding(ChooseCopyForm)))
  }

  def findCopyInfoP() = VisitAction { implicit req =>
    Binding(ChooseCopyForm, req) match {
      case ib: InvalidBinding => Ok(views.html.books.findCopyInfo(ib))
      case vb: ValidBinding => {
        val lookupBarcode: String = vb.valueOf(ChooseCopyForm.barcode)
        Redirect(routes.Books.copyInfo(lookupBarcode))
      }
    }
  }

  /**
   * Regex: /books/inventory
   *
   * Displays all of the titles in stock as well as certain information about each title.
   */
  def inventory() = VisitAction { implicit req =>
    val titles = DataStore.pm.query[Title].executeList.sortWith((c1, c2) => c1.name < c2.name)

    val rows: List[(String, String, String, String, String)] = titles.map(ti => {
      (ti.name, (ti.howManyCopies() - ti.howManyDeleted()).toString,
        ti.howManyCheckedOut().toString, ti.howManyLost().toString, (ti.howManyCopies() - (ti.howManyCheckedOut() + ti.howManyDeleted() + ti.howManyLost())).toString)
    })
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

    // TODO: this should probably be a Call, not a String
    override def cancelTo = Some(Call(Method.GET, "/books/editTitle"))

    def fields = List(name, author, publisher, numPages, dimensions, weight, imageUrl)
  }

  /**
   * Regex: /books/editTitleHelper/:isbn
   *
   * A form that allows the user to alter information about a title with a certain isbn.
   */
  def editTitleHelper(isbn: String) = VisitAction { implicit request =>
    // TODO: what if there's no title?
    val title = Title.getByIsbn(isbn).get
    Ok(views.html.books.editTitleHelper(Binding(new EditTitleForm(title.name, title.author, title.publisher, title.numPages, title.dimensions, title.weight))))
  }

  def editTitleHelperP(isbn: String) = VisitAction { implicit request =>
    // TODO: what if there's no title?
    val title = Title.getByIsbn(isbn).get
    val f = new EditTitleForm(title.name, title.author, title.publisher, title.numPages, title.dimensions, title.weight)
    Binding(f, request) match {
      case ib: InvalidBinding => Ok(views.html.books.editTitleHelper(ib))
      case vb: ValidBinding => {
        title.name = vb.valueOf(f.name)
        title.author = vb.valueOf(f.author)
        title.publisher = vb.valueOf(f.publisher)
        title.numPages = vb.valueOf(f.numPages)
        title.dimensions = vb.valueOf(f.dimensions)
        title.weight = vb.valueOf(f.weight)
        title.lastModified = LocalDateTime.now
        DataStore.pm.makePersistent(title)

        vb.valueOf(f.imageUrl) match {
          case Some(url) => try {
            downloadImage(url, isbn)
            Redirect(routes.App.index()).flashing("message" -> "Title updated successfully")
          } catch {
            case e: Exception => Redirect(routes.App.index()).flashing("error" -> "Image not downloaded. Edit the tite to try downloading again")
          }
          case None => Redirect(routes.App.index()).flashing("message" -> "Title updated successfully")
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

  /**
   * Regex: /books/editTitle
   *
   * A form that redirects a user to /books/editTitleHelper/:isbn based on the isbn they enter here.
   */
  def editTitle() = VisitAction { implicit req =>
    Ok(views.html.books.editTitle(Binding(ChooseTitleForm)))
  }

  def editTitleP() = VisitAction { implicit req =>
    Binding(ChooseTitleForm, req) match {
      case ib: InvalidBinding => Ok(views.html.books.editTitle(ib))
      case vb: ValidBinding => {
        val lookupIsbn: String = vb.valueOf(ChooseTitleForm.isbn)
        Redirect(routes.Books.editTitleHelper(lookupIsbn))
      }
    }
  }

  // Helper Method
  def makeBarcode(barcode: String): Barcode = {
    val b: Barcode128 = new Barcode128()
    b.setCode(barcode)
    b.setAltText(barcode)
    return b
  }

  // Helper Method
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

  // Helper Method
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
      val barcodeOffset = (labelWidth - img.getPlainWidth()) / 2
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

  // Helper Method
  val tempBarcode = makeBarcode("1234567890123-200-00001")

  // Helper Method
  val testBarcodes = List((tempBarcode, "123456788901234567890123456789012345678901234567890", "abcdefghijklmnopqrstuvwxyz123456789012345678901234567890", "qwertyuiopasdfghjklzxcvbnm098765432112345678901234567890"),
    (tempBarcode, "Name", "Author", "Publisher"), (tempBarcode, "Herp", "Derp", "Snerp"), (tempBarcode, "Welp", "Foo", "Bar"))

  val longTestBarcodes = testBarcodes ++ testBarcodes ++ testBarcodes ++ testBarcodes ++ testBarcodes ++ testBarcodes ++ testBarcodes ++ testBarcodes ++ testBarcodes

  /**
   * Regex: /books/addTitleToPrintQueue/:isbn/:cR
   *
   * Adds a title to the print queue and redirects the user to a print queue helper
   */
  def addTitleToPrintQueue(isbn: String, copyRange: String) = VisitAction { implicit request =>
    Title.getByIsbn(isbn) match {
      case None => Redirect(routes.Books.addTitleToPrintQueueHelper()).flashing("error" -> "Title not found")
      case Some(t) => {
        try {
          sanatizeCopyRange(copyRange)
          val l = new LabelQueueSet(request.visit.role.getOrElse(null), t, copyRange)
          DataStore.pm.makePersistent(l)
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

  /**
   * Regex: /books/addTitleToPrintQueueHelper
   *
   * A form page that allows the user to add certain titles and page ranges to a printer setup.
   */
  def addTitleToPrintQueueHelper() = VisitAction { implicit request =>
    Ok(views.html.books.addTitleToPrintQueueHelper(Binding(AddTitleToPrintQueueForm)))
  }

  def addTitleToPrintQueueHelperP() = VisitAction { implicit request =>
    Binding(AddTitleToPrintQueueForm, request) match {
      case ib: InvalidBinding => Ok(views.html.books.addTitleToPrintQueueHelper(ib))
      case vb: ValidBinding => {
        val lookupIsbn: String = vb.valueOf(AddTitleToPrintQueueForm.isbn)
        val copyRange: String = vb.valueOf(AddTitleToPrintQueueForm.copyRange)
        Redirect(routes.Books.addTitleToPrintQueue(lookupIsbn, copyRange))
      }
    }
  }

  /**
   * Regex: /books/viewPrintQueue
   *
   * Displays the items in the current print queue.
   */
  def viewPrintQueue() = VisitAction { implicit request =>
    val labelSets = DataStore.pm.query[LabelQueueSet].executeList
    val rows: List[(String, String, String, Long)] = labelSets.map(ls => { (ls.title.name, ls.title.isbn, ls.copyRange, ls.id) })
    Ok(views.html.books.viewPrintQueue(rows))
  }

  def removeFromPrintQueue(id: Long) = VisitAction { implicit request =>
    LabelQueueSet.getById(id) match {
      case None => Redirect(routes.Books.viewPrintQueue()).flashing("error" -> "ID not found")
      case Some(l) => {
        DataStore.pm.deletePersistent(l)
        Redirect(routes.Books.viewPrintQueue()).flashing("message" -> "Labels removed from print queue")
      }
    }
  }

  // Helper Method
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

  // Helper Method
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

  /**
   * Regex: /books/printEntireQueue
   *
   * Prints all of the items in the print queue.
   */
  def printEntireQueue() = VisitAction { implicit request =>
    DataStore.execute { pm =>
      val labelQueueSets = pm.query[LabelQueueSet].executeList
      print(labelQueueSets)
      for (x <- labelQueueSets) {
        pm.deletePersistent(x)
      }
      Ok.sendFile(content = new java.io.File("public/printable.pdf"), inline = true)
    }
  }

  /**
   * Regex: /books/deleteCopy/:barcode
   *
   * Removes the copy with given barcode from the database and redirects the user
   * to the deleteCopyHelper
   */
  def deleteCopy(barcode: String) = VisitAction { implicit request =>
    Copy.getByBarcode(barcode) match {
      case None => Redirect(routes.Books.deleteCopyHelper()).flashing("error" -> "Copy not found")
      case Some(c) =>
        DataStore.execute { pm =>
          val cand = QCheckout.candidate
          pm.query[Checkout].filter(cand.copy.eq(c).and(cand.endDate.eq(null.asInstanceOf[java.sql.Date]))).executeOption() match {
            case None => {
              c.deleted = true
              pm.makePersistent(c)
            }
            case Some(ch) => {
              ch.endDate = LocalDate.now()
              pm.makePersistent(ch)
              c.deleted = true
              pm.makePersistent(c)
            }
          }
        }
        Redirect(routes.Books.deleteCopyHelper()).flashing("message" -> "Copy deleted")
    }
  }

  /**
   * Regex: /books/deleteCopyHelper
   *
   * A form page that allows the user to delete the current copy.
   */
  def deleteCopyHelper() = VisitAction { implicit req =>
    Ok(views.html.books.deleteCopyHelper(Binding(ChooseCopyForm)))
  }

  def deleteCopyHelperP() = VisitAction { implicit req =>
    Binding(ChooseCopyForm, req) match {
      case ib: InvalidBinding => Ok(views.html.books.deleteCopyHelper(ib))
      case vb: ValidBinding => {
        val lookupBarcode: String = vb.valueOf(ChooseCopyForm.barcode)
        Redirect(routes.Books.deleteCopy(lookupBarcode))
      }
    }
  }

  /**
   * Regex: /books/deleteTitle/:isbn
   *
   * Deletes the title with given isbn from the database and redirects the user
   * to the deleteTitleHelper controller
   */
  def deleteTitle(isbn: String) = VisitAction { implicit request =>
    Title.getByIsbn(isbn) match {
      case None => Redirect(routes.Books.deleteTitleHelper()).flashing("error" -> "Title not found")
      case Some(t) => DataStore.execute { pm =>
        val cand = QPurchaseGroup.candidate
        val pg = pm.query[PurchaseGroup].filter(cand.title.eq(t)).executeList()
        if (pg.isEmpty) {
          pm.deletePersistent(t)
          Redirect(routes.Books.deleteTitleHelper()).flashing("message" -> "Title successfully deleted.")
        } else {
          Redirect(routes.Books.deleteTitleHelper()).flashing("error" -> "Books of this title purchased. Contact your system administrator to remove.")
        }
      }
    }
  }

  /**
   * Regex: /books/deleteTitleHelper
   *
   * A form page that allows the user to delete titles from the database.
   */
  def deleteTitleHelper() = VisitAction { implicit req =>
    Ok(views.html.books.deleteTitleHelper(Binding(ChooseTitleForm)))
  }

  def deleteTitleHelperP() = VisitAction { implicit req =>
    Binding(ChooseTitleForm, req) match {
      case ib: InvalidBinding => Ok(views.html.books.deleteTitleHelper(ib))
      case vb: ValidBinding => {
        val lookupIsbn: String = vb.valueOf(ChooseTitleForm.isbn)
        Redirect(routes.Books.deleteTitle(lookupIsbn))
      }
    }
  }
}
