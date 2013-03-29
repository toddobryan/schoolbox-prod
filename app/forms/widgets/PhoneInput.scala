package forms.widgets

import scala.xml._
import forms.validators.ValidationError

class PhoneInput(
  required: Boolean,
  attrs: MetaData = Null) extends Widget(required, attrs) {
  
  def render(name: String, value: Seq[String], attrList: MetaData = Null) = {
    <input type="text" name={ name } value={if(value.isEmpty) "" else value(0) } class="phone" placeholder="###-###-####" onkeyup="chkHyphen(this, [3,7]);"></input>
  }

  override def scripts: NodeSeq = 
    <script type="text/javascript">
	jQuery(function($){{
		$('.phone').mask('999-999-9999', {{placeholder:' '}});
	}});
  </script>

}