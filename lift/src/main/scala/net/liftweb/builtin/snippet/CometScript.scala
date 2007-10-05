package net.liftweb.builtin.snippet


/*                                                *\
 (c) 2007 WorldWide Conferencing, LLC
 Distributed under an Apache License
 http://www.apache.org/licenses/LICENSE-2.0
\*                                                 */
   
import net.liftweb.http._
import scala.xml._

class CometScript {
  def javaScript: NodeSeq = {
    val uri = S.request.contextPath + S.request.uri
    
    (<script type="text/javascript">
    // {Unparsed(
    """<![CDATA[
      function lift_handlerSuccessFunc(foo) {
	setTimeout("lift_cometEntry();",100);
      }
    
    function lift_handlerFailureFunc(foo) {
      // if we fail, wait 10 seconds to retry
      setTimeout("lift_cometEntry();",10000);
    }
    
    function lift_cometEntry() {
      new Ajax.Request('"""+uri+"""', { asynchronous:true, onSuccess:lift_handlerSuccessFunc, onFailure:lift_handlerFailureFunc, method: 'put', requestHeaders:{ Accept:'text/javascript' }});
    }
    // ]]>""")}
</script>)
  }
  
  def bodyAttr: MetaData = new UnprefixedAttribute("onload", "lift_cometEntry();", Null)
}
