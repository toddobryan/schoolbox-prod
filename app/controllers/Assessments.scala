package controllers

import play.api.mvc.Controller
import util._
import scala.util.Random
import play.api.data._
import play.api.data.Forms._



object Assessments extends Controller {
  
	var rand = new Random
	var first=rand.nextInt(9)+1
  	var second=rand.nextInt(9)+1
  	var otherChoice= rand.nextInt(100)
  	var ans=first+second
    
	
	def menu() = DbAction { implicit req =>
	  
	  rand = new Random
	  first=rand.nextInt(9)+1
	  second=rand.nextInt(9)+1
	  otherChoice= rand.nextInt(100)
	  ans=first+second
	  Ok(views.html.assessments.AssessmentsMenu("solve", ansForm, -1, None, first, second, ans, otherChoice))
	}
	
	val ansForm = Form {
    		"answer" -> number
    }
	
	def checkAnswer(temp: Int) = DbAction { implicit req =>
	    ansForm.bindFromRequest.fold( 
	        errors => {
	          BadRequest(views.html.assessments.AssessmentsMenu("solve", errors,-1, None, first, second, ans, otherChoice))
	        },
	        value => {
	          if (value == ans) {
	        	  Ok(views.html.assessments.AssessmentsMenu("solve", ansForm, -1, Some(0), first, second, ans, otherChoice))
	          } else {
	        	  Ok(views.html.assessments.AssessmentsMenu("solve", ansForm, -1, Some(1), first, second, ans, otherChoice))
	          }
	        }
	  	)
	}
	
	def newQuestion() = DbAction { implicit req =>
	  rand = new Random
	  first=rand.nextInt(9)+1
	  second=rand.nextInt(9)+1
	  otherChoice= rand.nextInt(100)
	  ans=first+second
	  Ok(views.html.assessments.AssessmentsMenu("solve", ansForm, -1, None, first, second, ans, otherChoice))
	}
}