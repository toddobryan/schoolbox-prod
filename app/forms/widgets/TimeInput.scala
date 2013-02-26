package forms.widgets

import scala.xml._
import java.util.UUID
import forms.validators.ValidationError
import java.sql.Time

class TimeInput(
  required: Boolean,
  attrs: MetaData = Null) extends Widget(required, attrs) {

  def render(name: String, value: Seq[String], attrList: MetaData = Null) = {
    <input type="text" name={ name } placeholder="hh:mm AM/PM" class="timepicker">{ if (value.isEmpty) "" else value(0) }</input>
  }
  
  override def scripts: NodeSeq = 
  <script>
    $(function() {{
      $('.timepicker').timepicker({{
		  showPeriod: true,
		  showLeadingZero: true
      }});
    }});
  </script>
}