package forms

import scala.xml._
import scala.xml.NodeSeq.seqToNodeSeq

import fields._
import validators.ValidationError

abstract class Form {
  // TODO: check that all names are unique
  def fields: List[Field[_]]
  def validate(data: ValidBinding): ValidationError = new ValidationError(Nil)
  def cancelTo: Option[Call] = None
  def submitMethod: FormMethod = Method.POST
  def submitUrl: Option[String] = None
  def prefix: Option[String] = None
  def autoId: Option[String] = Some("id_%s")
  def submitText = "Submit"
  def includeCancel = true
  def cancelText = "Cancel"
    
  protected def methodPlusAction(overrideSubmit: Option[FormCall]): 
      (FormMethod, Option[String]) = overrideSubmit match {
    case Some(FormCall(meth, url)) => (meth, url)
    case None => (submitMethod, None)
  }
      
  def render(bound: Binding, overrideSubmit: Option[FormCall]=None, legend: Option[String]=None): NodeSeq = {
    val (method: FormMethod, action: Option[String]) = methodPlusAction(overrideSubmit)
    this.scripts ++
    <form method={ method.forForm } action={ action.map(Text(_)) } class="form-horizontal well span7 offset1" >
      <fieldset>
    	{this.scripts}
        { legend.map(txt => <legend>{ txt }</legend>).getOrElse(NodeSeq.Empty) }
        { bound.formErrors.render }
        { fields.flatMap(_.render(bound)) }
        { actions }
      </fieldset>
    </form>
  }
  
  def scripts: NodeSeq = {
    fields.flatMap(_.widget.scripts).distinct
  }
  
  def actions: NodeSeq = {
    <div class="form-actions">
      <button type="submit" class="btn btn-primary">{ submitText }</button>
      { 
        if (!includeCancel) NodeSeq.Empty
        else {
          val onClick = cancelTo.map(call => s"window.location.href=${call.url}").getOrElse("window.history.back()")
          <button type="button" class="btn" onclick={ onClick }>{ cancelText }</button>
        }
      }
    </div>
  }
        
  def addPrefix(fieldName: String): String = {
    prefix.map(p => "%s-%s".format(p, fieldName)).getOrElse(fieldName)
  }
  
  def addInitialPrefix(fieldName: String): String = {
    "initial-%s".format(addPrefix(fieldName))
  }
}


