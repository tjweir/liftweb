/*
 * Copyright 2006-2009 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */
package net.liftweb.http

import _root_.javax.servlet.http.{HttpServlet, HttpServletRequest , HttpServletResponse, HttpSession, Cookie}
import _root_.scala.collection.mutable.{HashMap, ListBuffer}
import _root_.scala.xml.{NodeSeq, Elem, Text, UnprefixedAttribute, Null, MetaData,
                         PrefixedAttribute,
                         Group, Node, HasKeyValue}
import _root_.scala.collection.immutable.{ListMap, TreeMap}
import _root_.net.liftweb.util.{Helpers, ThreadGlobal, LoanWrapper, Box, Empty, Full, Failure,
                                Log, JSONParser, NamedPartialFunction, NamedPF, AttrHelper}
import Helpers._
import js._
import _root_.java.io.InputStream
import _root_.java.util.{Locale, TimeZone, ResourceBundle}
import _root_.java.util.concurrent.atomic.AtomicLong
import _root_.net.liftweb.builtin.snippet._

trait HasParams {
  def param(name: String): Box[String]
}

/**
 * An object representing the current state of the HTTP request and response.
 * It uses the DynamicVariable construct such that each thread has its own
 * local session info without passing a huge state construct around. The S object
 * is initialized by LiftSession on request startup.
 *
 * @see LiftSession
 * @see LiftFilter
 */
object S extends HasParams {
  /**
   * RewriteHolder holds a partial function that re-writes an incoming request. It is
   * used for per-session rewrites, as opposed to global rewrites, which are handled
   * by the LiftRules.rewrite RulesSeq. This case class exists so that RewritePFs may
   * be manipulated by name. See S.addSessionRewriter for example usage.
   *
   * @see #sessionRewriter
   * @see #addSessionRewriter
   * @see #clearSessionRewriter
   * @see #removeSessionRewriter
   * @see LiftRules#rewrite
   */
  case class RewriteHolder(name: String, rewrite: LiftRules.RewritePF)

  /**
   * DispatchHolder holds a partial function that maps a Req to a LiftResponse. It is
   * used for per-session dispatch, as opposed to global dispatch, which are handled
   * by the LiftRules.dispatch RulesSeq. This case class exists so that DispatchPFs may
   * be manipulated by name. See S.addHighLevelSessionDispatcher for example usage.
   *
   * @see LiftResponse
   * @see LiftRules#dispatch
   * @see #highLevelSessionDispatchList
   * @see #addHighLevelSessionDispatcher
   * @see #removeHighLevelSessionDispatcher
   * @see #clearHighLevelSessionDispatcher
   */
  case class DispatchHolder(name: String, dispatch: LiftRules.DispatchPF)

  /**
   * The CookieHolder class holds information about cookies to be sent during
   * the session, as well as utility methods for adding and deleting cookies. It
   * is used internally.
   *
   * @see #_responseCookies
   * @see #_init
   * @see #addCookie
   * @see #deleteCookie
   * @see #receivedCookies
   * @see #responseCookies
   * @see #findCookie
   */
  case class CookieHolder(inCookies: List[Cookie], outCookies: List[Cookie]) {
    def add(in: Cookie) = CookieHolder(inCookies, in :: outCookies.filter(_.getName != in.getName))
    def delete(name: String) = {
      val c = new Cookie(name, "")
      c.setMaxAge(0)
      add(c)
    }
    def delete(old: Cookie) = {
      val c = old.clone().asInstanceOf[Cookie]
      c.setMaxAge(0)
      c.setValue("")
      add(c)
    }
  }

  /*
   * The current session state is contained in the following val/vars:
   */

  /**
   * Holds the current Req (request) on a per-thread basis.
   * @see Req
   */
  private val _request = new ThreadGlobal[Req]

  /**
   * Holds the current functions mappings for this session.
   *
   * @see #functionMap
   * @see #addFunctionMap
   * @see #clearFunctionMap
   */
  private val _functionMap = new ThreadGlobal[HashMap[String, AFuncHolder]]

  /**
   * This is simply a flag so that we know whether or not the state for the S object
   * has been initialized for our current scope.
   *
   * @see #inStatefulScope_?
   * @see #initIfUninitted
   */
  private val inS = (new ThreadGlobal[Boolean]).set(false)

  /**
   * The snippetMap holds mappings from snippet names to snippet functions. These mappings
   * are valid only in the current request. This val
   * is typically modified using the mapSnippet method.
   *
   * @see #mapSnippet
   * @see #locateMappedSnippet
   */
  private val snippetMap = new ThreadGlobal[HashMap[String, NodeSeq => NodeSeq]]

  /**
   * Holds the attributes that are set on the current snippet tag. Attributes are available
   * to snippet functions via the S.attr and S.attrs methods.
   *
   * @see #attrs
   * @see #attr
   */
  private val _attrs = new ThreadGlobal[List[(Either[String, (String, String)], String)]]

  /**
   * Holds the per-request LiftSession instance.
   *
   * @see LiftSession
   * @see #session
   */
  private val _sessionInfo = new ThreadGlobal[LiftSession]

  /**
   * Holds a list of ResourceBundles for this request.
   *
   * @see #resourceBundles
   * @see LiftRules#resourceNames
   * @see LiftRules#resourceBundleFactories
   */
  private val _resBundle = new ThreadGlobal[List[ResourceBundle]]
  private val _liftCoreResBundle = new ThreadGlobal[Box[ResourceBundle]]
  private val _stateSnip = new ThreadGlobal[HashMap[String, StatefulSnippet]]
  private val _responseHeaders = new ThreadGlobal[ResponseInfoHolder]
  private val _responseCookies = new ThreadGlobal[CookieHolder]
  private val _lifeTime = new ThreadGlobal[Boolean]

  private object postFuncs extends RequestVar(new ListBuffer[() => Unit])
  private object p_queryLog extends RequestVar(new ListBuffer[(String, Long)])
  private object p_notice extends RequestVar(new ListBuffer[(NoticeType.Value, NodeSeq, Box[String])])

  /**
   * This function returns true if the S object has been initialized for our current scope. If
   * the S object has not been initialized then functionality on S will not work.
   */
  def inStatefulScope_? : Boolean = inS.value

  /**
   * Get a Req representing our current HTTP request.
   *
   * @return A Full(Req) if S has been initialized, Empty otherwise.
   *
   * @see Req
   */
  def request: Box[Req] = Box !! _request.value

  /**
   * @return a List of any Cookies that have been set for this Response. If you want
   * a specific cookie, use findCookie.
   *
   * @see javax.servlet.http.Cookie
   * @see #findCookie(String)
   * @see #addCookie(Cookie)
   * @see #deleteCookie(Cookie)
   * @see #deleteCookie(String)
   */
  def receivedCookies: List[Cookie] =
  for (rc <- Box.legacyNullTest(_responseCookies.value).toList; c <- rc.inCookies)
  yield c.clone().asInstanceOf[Cookie]

  /**
   * Finds a cookie with the given name that was sent in the request.
   *
   * @param name - the name of the cookie to find
   *
   * @return Full(cookie) if the cookie exists, Empty otherwise
   *
   * @see javax.servlet.http.Cookie
   * @see #receivedCookies
   * @see #addCookie(Cookie)
   * @see #deleteCookie(Cookie)
   * @see #deleteCookie(String)
   */
  def findCookie(name: String): Box[Cookie] =
  Box.legacyNullTest(_responseCookies.value).flatMap(
    rc => Box(rc.inCookies.filter(_.getName == name)).
    map(_.clone().asInstanceOf[Cookie]))

  /**
   * @return a List of any Cookies that have been added to the response to be sent
   * back to the user. If you want the cookies that were sent with the request, see
   * receivedCookies.
   *
   * @see javax.servlet.http.Cookie
   * @see #receivedCookies
   */
  def responseCookies: List[Cookie] = Box.legacyNullTest(_responseCookies.value).
  toList.flatMap(_.outCookies)

  /**
   * Adds a Cookie to the List[Cookies] that will be sent with the Response.
   *
   * If you wish to delete a Cookie as part of the Response, use the deleteCookie
   * method.
   *
   * An example of adding and removing a Cookie is:
   *
   * <pre name="code" class="scala" >
   * import javax.servlet.http.Cookie
   *
   * class MySnippet {
   *   final val cookieName = "Fred"
   *
   *   def cookieDemo (xhtml : NodeSeq) : NodeSeq = {
   *     var cookieVal = S.findCookie(cookieName).map(_.getvalue) openOr ""
   *
   *     def setCookie() {
   *       val cookie = new Cookie(cookieName, cookieVal)
   *       cookie.setMaxAge(3600) // 3600 seconds, or one hour
   *       S.addCookie(cookie)
   *     }
   *
   *     bind("cookie", xhtml,
   *          "value" -> SHtml.text(cookieVal, cookieVal = _),
   *          "add" -> SHtml.submit("Add", setCookie)
   *          "remove" -> SHtml.link(S.uri, () => S.deleteCookie(cookieName), "Delete Cookie")
   *     )
   *   }
   * }
   * </pre>
   *
   * @see javax.servlet.http.Cookie
   * @see #deleteCookie(Cookie)
   * @see #deleteCookie(String)
   * @see #responseCookies
   */
  def addCookie(cookie: Cookie) {
    Box.legacyNullTest(_responseCookies.value).foreach(rc =>
      _responseCookies.set(rc.add(cookie))
    )
  }

  /**
   * Deletes the cookie from the user's browser.
   *
   * @param cookie the Cookie to delete
   *
   * @see javax.servlet.http.Cookie
   * @see #addCookie(Cookie)
   * @see #deleteCookie(String)
   */
  def deleteCookie(cookie: Cookie) {
    Box.legacyNullTest(_responseCookies.value).foreach(rc =>
      _responseCookies.set(rc.delete(cookie))
    )
  }

  /**
   * Deletes the cookie from the user's browser.
   *
   * @param name the name of the cookie to delete
   *
   * @see javax.servlet.http.Cookie
   * @see #addCookie(Cookie)
   * @see #deleteCookie(Cookie)
   */
  def deleteCookie(name: String) {
    Box.legacyNullTest(_responseCookies.value).foreach(rc =>
      _responseCookies.set(rc.delete(name))
    )
  }


  /**
   * Find a template based on the snippet attribute "template"
   */
  // TODO: Is this used anywhere? - DCB
  def templateFromTemplateAttr: Box[NodeSeq] =
  for (templateName <- attr("template") ?~ "Template Attribute missing";
       val tmplList = templateName.roboSplit("/");
       template <- TemplateFinder.findAnyTemplate(tmplList) ?~
       "couldn't find template") yield template


  /**
   * Returns the Locale for this request based on the LiftRules.localeCalculator
   * method.
   *
   * @see LiftRules.localeCalculator(HttpServletRequest)
   * @see java.util.Locale
   */
  def locale: Locale = LiftRules.localeCalculator(servletRequest)

  /**
   * Return the current timezone based on the LiftRules.timeZoneCalculator
   * method.
   *
   * @see LiftRules.timeZoneCalculator(HttpServletRequest)
   * @see java.util.TimeZone
   */
  def timeZone: TimeZone =
  LiftRules.timeZoneCalculator(servletRequest)

  /**
   * @return <code>true</code> if this response should be rendered in
   * IE6/IE7 compatibility mode.
   *
   * @see LiftSession.ieMode
   * @see LiftRules.calcIEMode
   * @see Req.isIE6
   * @see Req.isIE7
   * @see Req.isIE8
   * @see Req.isIE
   */
  def ieMode: Boolean = session.map(_.ieMode.is) openOr false // LiftRules.calcIEMode()

  /**
   * Return a List of the LiftRules.DispatchPF functions that are set for this
   * session. See addHighLevelSessionDispatcher for an example of how these are
   * used.
   *
   * @see LiftRules.DispatchPF
   * @see #addHighLevelSessionDispatcher(String,LiftRules.DispatchPF)
   * @see #removeHighLevelSessionDispatcher(String)
   * @see #clearHighLevelSessionDispatcher
   */
  def highLevelSessionDispatcher: List[LiftRules.DispatchPF] = highLevelSessionDispatchList.map(_.dispatch)

  /**
   * Return the list of DispatchHolders set for this session.
   *
   * @see DispatchHolder
   */
  def highLevelSessionDispatchList: List[DispatchHolder] =
  session map (_.highLevelSessionDispatcher.toList.map(t => DispatchHolder(t._1, t._2))) openOr Nil

  /**
   * Adds a dispatch function for the current session, as opposed to a global
   * dispatch through LiftRules.dispatch. An example would be if we wanted a user
   * to be able to download a document only when logged in. First, we define
   * a dispatch function to handle the download, specific to a given user:
   *
   * <pre name="code" class="scala" >
   * def getDocument(userId : Long)() : Box[LiftResponse] = { ... }
   * </pre>
   *
   * Then, in the login/logout handling snippets, we could install and remove
   * the custom dispatch as appropriate:
   *
   * <pre name="code" class="scala" >
   *   def login(xhtml : NodeSeq) : NodeSeq = {
   *     def doAuth () {
   *       ...
   *       if (user.loggedIn_?) {
   *         S.addHighLevelSessionDispatcher("docDownload", {
   *           case Req(List("download", "docs"), _, _) => getDocument(user.id)
   *         })
   *     }
   *   }
   *
   *   def logout(xhtml : NodeSeq) : NodeSeq = {
   *     def doLogout () {
   *       ...
   *       S.removeHighLevelSessionDispatcher("docDownload")
   *       // or, if more than one dispatch has been installed, this is simpler
   *       S.clearHighLevelSessionDispatcher
   *     }
   *   }
   * </pre>
   *
   * It's important to note that per-session dispatch takes precedence over
   * LiftRules.dispatch, so you can override things set there.
   *
   * @param name A name for the dispatch. This can be used to remove it later by name.
   * @param disp The dispatch partial function
   *
   * @see LiftRules.DispatchPF
   * @see LiftRules.dispatch
   * @see #removeHighLevelSessionDispatcher
   * @see #clearHighLevelSessionDispatcher
   */
  def addHighLevelSessionDispatcher(name: String, disp: LiftRules.DispatchPF) =
  session map (_.highLevelSessionDispatcher += (name -> disp))

  /**
   * Removes a custom dispatch function for the current session. See
   * addHighLevelSessionDispatcher for an example of usage.
   *
   * @param name The name of the custom dispatch to be removed.
   *
   * @see LiftRules.DispatchPF
   * @see LiftRules.dispatch
   * @see #addHighLevelSessionDispatcher
   * @see #clearHighLevelSessionDispatcher
   */
  def removeHighLevelSessionDispatcher(name: String) =
  session map (_.highLevelSessionDispatcher -= name)

  /**
   * Clears all custom dispatch functions from the current session. See
   * addHighLevelSessionDispatcher for an example of usage.
   *
   * @see LiftRules.DispatchPF
   * @see LiftRules.dispatch
   * @see #addHighLevelSessionDispatcher
   * @see #clearHighLevelSessionDispatcher
   */
  def clearHighLevelSessionDispatcher = session map (_.highLevelSessionDispatcher.clear)


  /**
   * Return the list of RewriteHolders set for this session. See addSessionRewriter
   * for an example of how to use per-session rewrites.
   *
   * @see RewriteHolder
   * @see LiftRules#rewrite
   */
  def sessionRewriter: List[RewriteHolder] =
  session map (_.sessionRewriter.toList.map(t => RewriteHolder(t._1, t._2))) openOr Nil

  /**
   * Adds a per-session rewrite function. This can be used if you only want a particular rewrite
   * to be valid within a given session. Per-session rewrites take priority over rewrites set in
   * LiftRules.rewrite, so you can use this mechanism to override global functionality. For example,
   * you could set up a global rule to make requests for the "account profile" page go back to the home
   * page by default:
   *
   * <pre name="code" class="scala" >
   * package bootstrap.liftweb
   * ... imports ...
   * class Boot {
   *   def boot {
   *     LiftRules.rewrite.append {
   *       case RewriteRequest(ParsePath(List("profile")), _, _, _) =>
   *         RewriteResponse(List("index"))
   *     }
   *   }
   * }
   * </pre>
   *
   * Then, in your login snippet, you could set up a per-session rewrite to the correct template:
   *
   * <pre name="code" class="scala" >
   * def loginSnippet (xhtml : NodeSeq) : NodeSeq = {
   *   ...
   *   def doLogin () {
   *     ...
   *     S.addSessionRewriter("profile", {
   *       case RewriteRequest(ParsePath(List("profile")), _, _, _) =>
   *         RewriteResponse(List("viewProfile"), Map("user" -> user.id))
   *     }
   *     ...
   *   }
   *   ...
   * }
   * </pre>
   *
   * And in your logout snippet you can remove the rewrite:
   *
   * <pre name="code" class="scala" >
   *   def doLogout () {
   *     S.removeSessionRewriter("profile")
   *     // or
   *     S.clearSessionRewriter
   *   }
   * </pre>
   *
   *
   * @param name A name for the rewrite function so that it can be replaced or deleted later.
   * @rw The rewrite partial function
   *
   * @see LiftRules.rewrite
   * @see #sessionRewriter
   * @see #removeSessionRewriter
   * @see #clearSessionRewriter
   */
  def addSessionRewriter(name: String, rw: LiftRules.RewritePF) =
  session map (_.sessionRewriter += (name -> rw))

  /**
   * Removes the given per-session rewriter. See addSessionRewriter for an
   * example of usage.
   *
   * @see LiftRules.rewrite
   * @see #addSessionRewriter
   * @see #clearSessionRewriter
   */
  def removeSessionRewriter(name: String) =
  session map (_.sessionRewriter -= name)

  /**
   * Clears the per-session rewrite table. See addSessionRewriter for an
   * example of usage.
   *
   * @see LiftRules.rewrite
   * @see #addSessionRewriter
   * @see #removeSessionRewriter
   */
  def clearSessionRewriter = session map (_.sessionRewriter.clear)

  /**
   * Test the current request to see if it's a POST. This is a thin wrapper
   * over Req.post_?
   *
   * @return <code>true</code> if the request is a POST request, <code>false</code>
   * otherwise.
   */
  def post_? = request.map(_.post_?).openOr(false)

  /**
   * Localize the incoming string based on a resource bundle for the current locale. The
   * localized string is converted to an XML element if necessary via the <code>LiftRules.localizeStringToXml</code>
   * function (the default behavior is to wrap it in a Text element). If the lookup fails for a given resource
   * bundle (e.g. a null is returned), then the <code>LiftRules.localizationLookupFailureNotice</code>
   * function is called with the input string and locale.
   *
   * @param str the string or ID to localize
   *
   * @return A Full box containing the localized XML or Empty if there's no way to do localization
   *
   * @see #locale
   * @see #resourceBundles
   * @see LiftRules.localizeStringToXml
   * @see LiftRules.localizationLookupFailureNotice
   * @see #loc(String,NodeSeq)
   */
  def loc(str: String): Box[NodeSeq] =
  resourceBundles.flatMap(r => tryo(r.getObject(str) match {
        case null => LiftRules.localizationLookupFailureNotice.foreach(_(str, locale)); Empty
        case s: String => Full(LiftRules.localizeStringToXml(s))
        case g: Group => Full(g)
        case e: Elem => Full(e)
        case n: Node => Full(n)
        case ns: NodeSeq => Full(ns)
        case x => Full(Text(x.toString))
      }).flatMap(s => s)).find(e => true)

  /**
   * Localize the incoming string based on a resource bundle for the current locale,
   * with a default value to to return if localization fails.
   *
   * @param str the string or ID to localize
   * @param dflt the default string to return if localization fails
   *
   * @return the localized XHTML or default value
   *
   * @see #loc(String)
   */
  def loc(str: String, dflt: NodeSeq): NodeSeq = loc(str).openOr(dflt)

  /**
   * Get a List of the resource bundles for the current locale. The resource bundles are defined by
   * the LiftRules.resourceNames and LiftRules.resourceBundleFactories variables.
   *
   * @see LiftRules.resourceNames
   * @see LiftRules.resourceBundleFactories
   */
  def resourceBundles: List[ResourceBundle] = {
    _resBundle.value match {
      case Nil => {
          _resBundle.set(LiftRules.resourceNames.flatMap(name => tryo(
                List(ResourceBundle.getBundle(name, locale))
              ).openOr(
                NamedPF.applyBox((name, locale), LiftRules.resourceBundleFactories.toList).map(List(_)) openOr Nil
              )))
          _resBundle.value
        }
      case bundles => bundles
    }
  }

  /**
   * Get the lift core resource bundle for the current locale as defined by the
   * LiftRules.liftCoreResourceName varibale.
   *
   * @see LiftRules.liftCoreResourceName
   */
  def liftCoreResourceBundle: Box[ResourceBundle] =
  Box.legacyNullTest(_liftCoreResBundle.value).openOr {
    val rb = tryo(ResourceBundle.getBundle(LiftRules.liftCoreResourceName, locale))
    _liftCoreResBundle.set(rb)
    rb
  }

  /**
   * Get a localized string or return the original string.
   *
   * @param str the string to localize
   *
   * @return the localized version of the string
   *
   * @see #resourceBundles
   */
  def ?(str: String): String = ?!(str, resourceBundles)

  /**
   * Attempt to localize and then format the given string. This uses the String.format method
   * to format the localized string.
   *
   * @param str the string to localize
   * @param params the var-arg parameters applied for string formatting
   *
   * @return the localized and formatted version of the string
   *
   * @see String.format
   * @see #resourceBundles
   */
  def ?(str: String, params: Any *): String =
  if (params.length == 0)
  ?(str)
  else
  String.format(locale, ?(str), params.flatMap{case s: AnyRef => List(s) case _ => Nil}.toArray :_*)

  /**
   * Get a core lift localized string or return the original string
   *
   * @param str the string to localize
   *
   * @return the localized version of the string
   */
  def ??(str: String): String = ?!(str, liftCoreResourceBundle.toList)

  private def ?!(str: String, resBundle: List[ResourceBundle]): String = resBundle.flatMap(r => tryo(r.getObject(str) match {
        case s: String => Full(s)
        case _ => Empty
      }).flatMap(s => s)).find(s => true) getOrElse {
    LiftRules.localizationLookupFailureNotice.foreach(_(str, locale));
    str
  }

  /**
   * Test the current request to see if it's a GET. This is a thin wrapper on Req.get_?
   *
   * @return <code>true</code> if the request is a GET, <code>false</code> otherwise.
   *
   * @see Req.get_?
   */
  def get_? = request.map(_.get_?).openOr(false)

  /**
   * The URI of the current request (not re-written). The URI is the portion of the request
   * URL after the servlet context path. For example, with a servlet context path of "myApp",
   * Lift would return the following URIs for the given requests:
   *
   * <table>
   * <tr align=left>
   *   <th>HTTP request</th><th>URI</th>
   * </tr>
   * <tr>
   *   <td>http://foo.com/myApp/foo/bar.html<td><td>/foo/bar.html</td>
   * </tr>
   * <tr>
   *   <td>http://foo.com/myApp/test/<td><td>/test/</td>
   * </tr>
   * <tr>
   *   <td>http://foo.com/myApp/item.html?id=42<td><td>/item.html</td>
   * </tr>
   * </table>
   *
   * If you want the full URI, including the servlet context path, you should retrieve it
   * from the underlying HttpServletRequest. You could do something like:
   *
   * <pre name="code" class="scala" >
   *   val fullURI = S.request.map(_.request.getRequestURI) openOr ("Undefined")
   * </pre>
   *
   * The URI may be used to provide a link back to the same page as the current request:
   *
   * <pre name="code" class="scala" >
   *   bind(...,
   *        "selflink" -> SHtml.link(S.uri, { () => ... }, Text("Self link")),
   *        ...)
   * </pre>
   *
   * @see Req.uri
   * @see javax.servlet.http.HttpServletRequest.getRequestURI
   */
  def uri: String = request.map(_.uri).openOr("/")

  /**
   * Redirects the browser to a given URL. Note that the underlying mechanism for redirects is to
   * throw a ResponseShortcutException, so if you're doing the redirect within a try/catch block,
   * you need to make sure to either ignore the redirect exception or rethrow it. Two possible
   * approaches would be:
   *
   * <pre name="code" class="scala" >
   *   ...
   *   try {
   *     // your code here
   *     S.redirectTo(...)
   *   } catch {
   *     case e: Exception if !e.instanceOf[net.liftweb.http.ResponseShortcutException] => ...
   *   }
   * </pre>
   *
   * or
   *
   * <pre name="code" class="scala" >
   *   ...
   *   try {
   *     // your code here
   *     S.redirectTo(...)
   *   } catch {
   *     case rse: net.liftweb.http.ResponseShortcutException => throw rse
   *     case e: Exception => ...
   *   }
   * </pre>
   *
   * @param where The new URL to redirect to.
   *
   * @see ResponseShortcutException
   * @see #redirectTo(String, () => Unit)
   */
  def redirectTo[T](where: String): T = throw ResponseShortcutException.redirect(where)

  /**
   * Redirects the browser to a given URL and registers a function that will be executed when the browser
   * accesses the new URL. Otherwise the function is exactly the same as S.redirectTo(String), which has
   * example documentation. Note that if the URL that you redirect to must be part of your web application
   * or the function won't be executed. This is because the function is only registered locally.
   *
   * @param where The new URL to redirect to.
   * @param func The function to be executed when the redirect is accessed.
   *
   * @see #redirectTo(String)
   */
  def redirectTo[T](where: String, func: () => Unit): T =
  throw ResponseShortcutException.redirect(where, func)

  private[http] object oldNotices extends
  RequestVar[Seq[(NoticeType.Value, NodeSeq, Box[String])]](Nil)

  /**
   * Initialize the current request session. Generally this is handled by Lift during request
   * processing, but this method is available in case you want to use S outside the scope
   * of a request (standard HTTP or Comet).
   *
   * @param request The Req instance for this request
   * @param session the LiftSession for this request
   * @param f 
   */
  // TODO: what is f?
  def init[B](request: Req, session: LiftSession)(f: => B) : B = {
    _init(request,session)(() => f)
  }

  /**
   * The current LiftSession.
   */
  def session: Box[LiftSession] = Box.legacyNullTest(_sessionInfo.value)

  /**
   * Log a query for the given request.  The query log can be tested to see
   * if queries for the particular page rendering took too long. The query log
   * starts empty for each new request. This method can be used as a log function
   * for the net.liftweb.mapper.DB.addLogFunc method to enable logging of
   * Mapper queries. You would set it up in your bootstrap like:
   *
   * <pre name="code" class="scala" >
   * import net.liftweb.mapper.DB
   * import net.liftweb.http.S
   * class Boot {
   *   def boot {
   *     ...
   *     DB.addLogFunc(S.logQuery _)
   *     ...
   *   }
   * }
   * </pre>
   *
   * Note that the query log is simply stored as a List and is not sent to any output
   * byt default. To retrieve the List of query log items, use S.queryLog. You can also
   * provide your own analysis function that will process the query log vi S.addAnalyzer.
   *
   * @see #queryLog
   * @see #addAnalyzer
   * @see net.liftweb.mapper.DB.addLogFun((String,Long) => Any)
   */
  def logQuery(query: String, time: Long) = p_queryLog.is += (query, time)

  private[http] def snippetForClass(cls: String): Box[StatefulSnippet] =
  Box.legacyNullTest(_stateSnip.value).flatMap(_.get(cls))

  private[http] def setSnippetForClass(cls: String, inst: StatefulSnippet): Unit =
  Box.legacyNullTest(_stateSnip.value).foreach(_(cls) = inst)

  private[http] def unsetSnippetForClass(cls: String): Unit =
  Box.legacyNullTest(_stateSnip.value).foreach(_ -= cls)


  private var _queryAnalyzer: List[(Box[Req], Long,
                                    List[(String, Long)]) => Any] = Nil

  /**
   * Add a query analyzer (passed queries for analysis or logging). The analyzer
   * methods are executed with the request, total time to process the request, and
   * the List of query log entries once the current request completes.
   *
   * @see #logQuery
   * @see #queryLog
   */
  def addAnalyzer(f: (Box[Req], Long,
                      List[(String, Long)]) => Any): Unit =
  _queryAnalyzer = _queryAnalyzer ::: List(f)

  private var aroundRequest: List[LoanWrapper] = Nil

  private def doAround[B](ar: List[LoanWrapper])(f: => B): B =
  ar match {
    case Nil => f
    case x :: xs => x(doAround(xs)(f))
  }

  /**
   * You can wrap the handling of an HTTP request with your own wrapper.  The wrapper can
   * execute code before and after the request is processed (but still have S scope).
   * This allows for query analysis, etc. See S.addAround(LoanWrapper) for an example.
   * This version of the method takes a list of LoanWrappers that are applied in order.
   *
   * @see #addAround(LoanWrapper)
   * @see LoanWrapper
   */
  def addAround(lw: List[LoanWrapper]): Unit = aroundRequest = lw ::: aroundRequest

  /**
   * You can wrap the handling of an HTTP request with your own wrapper.  The wrapper can
   * execute code before and after the request is processed (but still have S scope).
   * This allows for query analysis, etc. Wrappers are chained, much like servlet filters,
   * so you can layer processing on the request. As an example, let's look at a wrapper that opens
   * a resource and makes it available via a RequestVar, then closes the resource when finished:
   *
   * <pre name="code" class="scala" >
   * import net.liftweb.http.{ResourceVar,S}
   * import net.liftweb.util.LoanWrapper
   *
   * // Where "ResourceType" is defined by you
   * object myResource extends ResourceVar[ResourceType](...)
   * 
   * class Boot {
   *   def boot {
   *     ...
   *     S.addAround(
   *       new LoanWrapper {
   *         def apply[T](f: => T) : T = {
   *           myResource(... code to open and return a resource instance ...)
   *           f() // This call propagates the request further down the "chain" for template processing, etc.
   *           myResource.is.close() // Release the resource
   *         }
   *       }
   *     )
   *     ...
   *   }
   * }
   * </pre>
   * 
   * @see #addAround(LoanWrapper)
   * @see LoanWrapper 
   */
  def addAround(lw: LoanWrapper): Unit = aroundRequest = lw :: aroundRequest

  /**
   * Get a list of the logged queries. These log entries are added via the logQuery method, which
   * has a more detailed explanation of usage.
   *
   * @see #logQuery(String,Long)
   * @see #addAnalyzer
   */
  def queryLog: List[(String, Long)] = p_queryLog.is.toList

  private def wrapQuery[B](f:() => B): B = {
    val begin = millis
    try {
      f()
    } finally {
      val time = millis - begin
      _queryAnalyzer.foreach(_(request, time, queryLog))
    }
  }

  /**
   * Sets a HTTP header attribute. For example, you could set a "Warn" header in
   * your response:
   *
   * <pre name="code" class="scala" >
   *   ...
   *   S.setHeader("Warn", "The cheese is old and moldy")
   *   ...
   * </pre>
   *
   * @see #getHeaders
   */
  def setHeader(name: String, value: String) {
    Box.legacyNullTest(_responseHeaders.value).foreach(
      rh =>
      rh.headers = rh.headers + (name -> value)
    )
  }

  /**
   * Returns the currently set HTTP headers as a List[(String, String)]. To retrieve
   * a specific header, use S.getHeader.
   *
   * @see #setHeader(String,String)
   * @see #getHeader(String)
   */
  def getHeaders(in: List[(String, String)]): List[(String, String)] = {
    Box.legacyNullTest(_responseHeaders.value).map(
      rh =>
      rh.headers.elements.toList :::
      in.filter{case (n, v) => !rh.headers.contains(n)}
    ).openOr(Nil)
  }

  /**
   * Returns the current set value of the given HTTP header as a Box.
   *
   * @param name The name of the HTTP header to retrieve
   * @return A Full(value) or Empty if the header isn't set
   *
   * @see #setHeader(String,String)
   * @see #getHeaders(List[(String,String)])
   */
  def getHeader(name : String) : Box[String] = {
    Box.legacyNullTest(_responseHeaders.value).map(
      rh => Box(rh.headers.get(name))
    ).openOr(Empty)
  }

  /**
   * Sets the document type for the response. If this is not set, the DocType for Lift responses
   * defaults to XHTML 1.0 Transitional.
   *
   * @see getDocType
   * @see ResponseInfo.docType
   * @see DocType
   */
  def setDocType(what: Box[String]) {
    Box.legacyNullTest(_responseHeaders.value).foreach(
      rh =>
      rh.docType = what
    )
  }

  /**
   * Returns the document type that was set for the response. The default is XHTML 1.0
   * Transitional.
   *
   * @see setDocType
   * @see DocType
   */
  def getDocType: (Boolean, Box[String]) = Box.legacyNullTest(_responseHeaders.value).map(
    rh => (rh.overrodeDocType, rh.docType)
  ).openOr( (false, Empty) )


  private object _skipDocType extends RequestVar(false)

  /**
   * When this is true, Lift will not emit a DocType definition at the start of the response
   * content. If you're sending XHTML and this is set to true, you need to include the DocType
   * in your template.
   *
   * @see #skipDocType_=(Boolean)
   */
  def skipDocType : Boolean = _skipDocType.is

  /**
   * Sets Lift's DocType behavior. If this is set to true, Lift will not emit a DocType definition
   * at the start of the response content. If you're sending XHTML and this is set to true, you need
   * to include the DocType in your template.
   *
   * @param skip Set to <code>true</code> to prevent Lift from emitting a DocType in its response
   *
   * @see #skipDocType
   */
  def skipDocType_=(skip: Boolean) {_skipDocType.set(skip)}

  /**
   * Adds a cleanup function that will be executed at the end of the request pocessing.
   * Exceptions thrown from these functions will be swallowed, so make sure to handle any
   * expected exceptions within your function.
   *
   * @param f The function to execute at the end of the request.
   */
  def addCleanupFunc(f: () => Unit): Unit = postFuncs.is += f

  private def _nest2InnerInit[B](f: () => B): B = {
    _functionMap.doWith(new HashMap[String, AFuncHolder]) {
      doAround(aroundRequest) {
        try {
          wrapQuery {
            f
          }
        } finally {
          postFuncs.is.foreach(f => tryo(f()))
        }
      }
    }
  }

  private def _innerInit[B](f: () => B): B = {
    _lifeTime.doWith(false) {
      _attrs.doWith(Nil) {
        snippetMap.doWith(new HashMap) {
          _resBundle.doWith(Nil) {
            _liftCoreResBundle.doWith(null){
              inS.doWith(true) {
                _stateSnip.doWith(new HashMap) {
                  _nest2InnerInit(f)
                }
              }
            }
          }
        }
      }
    }
  }

  /**
   * @return a List[Cookie] even if the underlying request's Cookies are null.
   */
  private def getCookies(request: Box[HttpServletRequest]): List[Cookie] =
  for (r <- (request).toList;
       ca <- Box.legacyNullTest(r.getCookies).toList;
       c <- ca) yield c

  private def _init[B](request: Req, session: LiftSession)(f: () => B): B =
  this._request.doWith(request) {
    _sessionInfo.doWith(session) {
      _responseHeaders.doWith(new ResponseInfoHolder) {
        RequestVarHandler(Full(session),
                          _responseCookies.doWith(CookieHolder(getCookies(servletRequest), Nil)) {
            _innerInit(f)
          }
        )
      }
    }
  }

  /**
   * This method is a convenience accessor for LiftRules.loggedInTest. You can define your own
   * function to check to see if a user is logged in there and this will call it.
   *
   * @see LiftRules.loggedInTest
   *
   * @return the value from executing LiftRules.loggedInTest, or <code>false</code> if a test function
   * is not defined.
   */
  def loggedIn_? : Boolean = LiftRules.loggedInTest.map(_.apply()) openOr false

  /**
   * Returns the 'Referer' HTTP header attribute. 
   */
  def referer: Box[String] = servletRequest.flatMap(r => Box.legacyNullTest(r.getHeader("Referer")))

  /**
   * Functions that are mapped to HTML elements are, by default,
   * garbage collected if they are not seen in the browser in the last 10 minutes (defined in LiftRules.unusedFunctionsLifeTime).
   * In some cases (e.g., JSON handlers), you may want to extend the
   * lifespan of the functions to the lifespan of the session.
   *
   * @param span If <code>true</code>, extend the mapped function lifetime to the life of the session
   * @param f A function to execute in the context of specified span
   *
   * @see LiftRules.unusedFunctionsLifeTime
   */
  def functionLifespan[T](span: Boolean)(f: => T): T =
  _lifeTime.doWith(span)(f)

  /**
   * Returns whether functions are currently extended to the lifetime of the session.
   *
   * @return <code>true</code> if mapped functions will currently last the life of the session.
   */
  def functionLifespan_? : Boolean = _lifeTime.box openOr false

  /**
   * Get a list of current attributes. Each attribute item is a pair of (key,value). The key
   * is an Either that depends on whether the attribute is prefixed or not. If the attribute
   * is prefixed, the key is a Right((prefix, name)). If the attribute is unprefixed then the
   * key is a Left(name). For example, the following table shows how various tag attributes
   * would be represented:
   *
   * <table>
   *   <tr>
   *     <th>Snippet Tag</th>
   *     <th>Parsed attrs</th>
   *   </tr>
   *   <tr>
   *     <td>&lt;lift:MySnippet testname="test" /&gt;</td>
   *     <td>List((Left("testname"), "test"))</td>
   *   </tr>
   *   <tr>
   *     <td>&lt;lift:MySnippet anchor:name="test" /&gt;</td>
   *     <td>List((Right(("anchor", "name")), "test"))</td>
   *   </tr>
   * </table>
   *
   * <p>The prefixedAttrsToMap method provides a convenient way to retrieve only attributes with
   * a given prefix. The prefixedAttrsToMetaData method can be used to add attributes onto an XML
   * node</p>
   *
   * @see #prefixedAttrsToMap(String)
   * @see #prefixedAttrsToMap(String,Map)
   * @see #prefixedAttrsToMetaData(String)
   * @see #prefixedAttrsToMetaData(String,Map)
   */
  def attrs: List[(Either[String, (String, String)], String)] = S._attrs.value match {
    case null => Nil
    case xs => xs
  }

  /**
   * Returns the S attributes that are prefixed by 'prefix' parameter as a Map[String, String]
   * that will be 'merged' with the 'start' Map
   *
   * @param prefix the prefix to be matched
   * @param start the initial Map
   *
   * @return Map[String, String]
   * 
   * @see #prefixedAttrsToMap(String)
   * @see #prefixedAttrsToMetaData(String)
   * @see #prefixedAttrsToMetaData(String,Map)
   * 
   */
  def prefixedAttrsToMap(prefix: String, start: Map[String, String]): Map[String, String] =
  attrs.reverse.flatMap {
    case (Right( (pre, name)), value) if pre == prefix => List((name, value))
    case _ => Nil
  }.foldRight(start){
    case ((name, value), at) => at + (name -> value)
  }

  /**
   * Returns the S attributes that are prefixed by 'prefix' parameter as a Map[String, String]
   *
   * @param prefix the prefix to be matched
   *
   * @return Map[String, String]
   *
   * @see #prefixedAttrsToMap(String,Map)
   * @see #prefixedAttrsToMetaData(String)
   * @see #prefixedAttrsToMetaData(String,Map)
   * 
   */
  def prefixedAttrsToMap(prefix: String): Map[String, String] =
  prefixedAttrsToMap(prefix: String, Map.empty)

  /**
   * <p>Returns the S attributes that are prefixed by 'prefix' parameter as a MetaData.
   * The start Map will be 'merged' with the Map resulted after prefix matching and
   * the result Map will be converted to a MetaData. The MetaData can be used to add attributes
   * back onto XML elements via Scala's '%' method. For example, if we wanted to add
   * attributes prefixed with "anchor" to any &lt;a&gt; elements we create, we could
   * do something like:</p>
   *
   * <pre name="code" class="scala" >
   *   val myLink = (<a href={...}>...</a>) % S.prefixedAttrsToMetaData("anchor", Map("id" -> "myAnchor"))
   * </pre>
   *
   * @param prefix the prefix to be matched
   * @param start the initial Map
   *
   * @return MetaData representing the combination of current attributes plus the start Map of attributes
   *
   * @see #prefixedAttrsToMap(String)
   * @see #prefixedAttrsToMap(String,Map)
   * @see #prefixedAttrsToMetaData(String)
   * 
   */
  def prefixedAttrsToMetaData(prefix: String, start: Map[String, String]): MetaData =
  mapToAttrs(prefixedAttrsToMap(prefix, start))

  /**
   * Similar with prefixedAttrsToMetaData(prefix: String, start: Map[String, String])
   * but there is no 'start' Map
   */
  def prefixedAttrsToMetaData(prefix: String): MetaData = prefixedAttrsToMetaData(prefix, Map.empty)

  /**
   * Converts a Map[String, String] into a MetaData instance. This can be used to
   * add attributes to an XML element based on a map of attribute->value pairs. See
   * prefixedAttrsToMetaData(String,Map) for an example.
   *
   * @param in The map of attributes
   * 
   * @return MetaData representing the Map of attributes as unprefixed attributes.
   *
   * @see #prefixedAttrsToMetaData(String,Map)
   * 
   */
  def mapToAttrs(in: Map[String, String]): MetaData =
  in.foldLeft[MetaData](Null) {
    case (md, (name, value)) => new UnprefixedAttribute(name, value, md)
  }

  /**
   * Converts the S.attrs to a Map[String, String]. The key of the map depends on whether
   * the attribute is prefixed or not. Prefixed attributes have keys of the form
   * "prefix:name", while unprefixed attributes have keys of the form "name". If you only want
   * attributes for a specific prefix, use prefixedAttrsToMap.
   *
   * @see #prefixedAttrsToMap(String)
   * @see #prefixedAttrsToMap(String,Map)
   */
  def attrsFlattenToMap: Map[String, String] = Map.empty ++ attrs.flatMap {
    case (Left(key), value) => List((key, value))
    case (Right((prefix, key)), value)=> List((prefix+":"+key, value))
    case _ => Nil
  }

  /**
   * Converts S.attrs attributes to a MetaData object that can be used to add
   * attributes to one or more XML elements. Similar to prefixedAttrsToMetaData, except
   * that it handles both prefixed and unprefixed attributes. This version of the method will
   * use all of the currently set attributes from S.attrs. If you want to filter it, use the
   * attrsToMetaData(String => Boolean) version, which allows you to specify a predicate
   * function for filtering. For example, if you want all of the current attributes to be
   * added to a div tag, you could do:
   *
   * <pre name="code" class="scala" >
   * val myDiv = (<div>{...}</div>) % S.attrsToMetaData
   * </pre>
   *
   * @return a MetaData instance representing all attributes in S.attrs
   *
   * @see #attrsToMetaData(String => Boolean)
   */
  def attrsToMetaData: MetaData = attrsToMetaData(ignore => true)

  /**
   * Similar to S.attrsToMetaData, but lets you specify a predicate function that filters the
   * generated MetaData. For example, if you only wanted the "id" attribute, you could do:
   *
   * <pre name="code" class="scala" >
   * val myDiv = (<div>{...}</div>) % S.attrsToMetaData(_.equalsIgnoreCase("id"))
   * </pre>
   *
   * @param predicate The predicate function which is executed for each attribute name. If the function
   * returns <code>true</code>, then the attribute is included in the MetaData.
   * 
   * @see #attrsToMetaData
   * 
   */
  def attrsToMetaData(predicate: String => Boolean): MetaData = {
    attrs.foldLeft[MetaData](Null) {
      case (md, (Left(name), value)) if (predicate(name))=> new UnprefixedAttribute(name, value, md)
      case (md, (Right((prefix, name)), value)) if (predicate(name)) => new PrefixedAttribute(prefix, name, value, md)
      case _ => Null
    }
  }

  /**
   * Find and process a template
   */
  def runTemplate(path: List[String]): Box[NodeSeq] =
  for {
    t <- TemplateFinder.findAnyTemplate(path) ?~ ("Couldn't find template "+path)
    sess <- session ?~ "No current session"
  } yield sess.processSurroundAndInclude(path.mkString("/", "/", ""), t)

  /**
   * Used to get an attribute by its name
   */
  object attr extends AttrHelper[Box] {
    type Info = String

    protected def findAttr(key: String): Option[Info] =
    attrs.find {
      case (Left(v), _) if v == key => true
      case _ => false
    }.map(_._2)

    protected def findAttr(prefix: String, key: String): Option[Info] =
    attrs.find {
      case (Right((p, n)), _) if (p == prefix && n == key) => true
      case _ => false
    }.map(_._2)

    protected def convert[T](in: Option[T]): Box[T] = Box(in)

    /**
     * Returns the unprefixed attribute value as an Option[NodeSeq]
     * for easy addition to the attributes
     */
    def ~(key: String): Option[NodeSeq] = apply(key).toOption.map(Text)

    /**
     * Returns the prefixed attribute value as an Option[NodeSeq]
     * for easy addition to the attributes
     */
    def ~(prefix: String, key: String): Option[NodeSeq] = apply(prefix, key).toOption.map(Text)
  }

  /**
   * Concatenates the 'attr' attributes with the existent once and then executes the f
   * function. The concatenation is not permanent, it will just exist for the duration of
   * f execution.
   */
  def setVars[T](attr: MetaData)(f: => T): T = {
    _attrs.doWith(attr.toList.map{
        case pa: PrefixedAttribute => (Right(pa.pre, pa.key), pa.value.text)
        case m => (Left(m.key), m.value.text)
      } ::: attrs)(f)
  }

  def initIfUninitted[B](session: LiftSession)(f: => B) : B = {
    if (inS.value) f
    else init(Req.nil,session)(f)
  }

  /**
   * Returns the LiftSession parameter denominated by 'what'
   */
  def get(what: String): Box[String] = session.flatMap(_.get[String](what))

  /**
   * Returns the HttpSession parameter denominated by 'what'
   */
  def getSessionAttribute(what: String): Box[String] = servletSession.flatMap(_.getAttribute(what) match {case s: String => Full(s) case _ => Empty})

  /**
   * Returns the HttpSession
   */
  def servletSession: Box[HttpSession] = session.flatMap(_.httpSession).or(servletRequest.map(_.getSession))

  /**
   * Returns 'type' S attribute
   */
  def invokedAs: String = (currentSnippet or attr("type")) openOr ""

  /**
   * Sets a HttpSession attribute
   */
  def setSessionAttribute(name: String, value: String) = servletSession.foreach(_.setAttribute(name, value))

  /**
   * Sets a LiftSession attribute
   */
  def set(name: String, value: String) = session.foreach(_.set(name,value))

  /**
   * Removes a HttpSession attribute
   */
  def unsetSessionAttribute(name: String) = servletSession.foreach(_.removeAttribute(name))

  /**
   * Removes a LiftSession attribute
   */
  def unset(name: String) = session.foreach(_.unset(name))

  /**
   * The current servlet request
   */
  def servletRequest: Box[HttpServletRequest] =
  request.flatMap(r => Box !! r.request)

  /**
   * The host that the request was made on
   */
  def hostName: String = servletRequest.map(_.getServerName).openOr("nowhere_123.com")

  /**
   * The host and path of the quest
   */
  def hostAndPath: String =
  servletRequest.map(r => (r.getScheme, r.getServerPort) match {
      case ("http", 80) => "http://"+r.getServerName+contextPath
      case ("https", 443) => "https://"+r.getServerName+contextPath
      case (sch, port) => sch + "://"+r.getServerName+":"+port+contextPath
    }).openOr("")

  /**
   * Get a map of the name/functions
   */
  def functionMap: Map[String, AFuncHolder] =
  Box.legacyNullTest(_functionMap.value).
  map(s => Map(s.elements.toList :_*)).openOr(Map.empty)

  /**
   * Clears the function map.  potentially very destuctive... use at own risk
   */
  def clearFunctionMap {Box.!!(_functionMap.value).foreach(_.clear)}

  /**
   * The current context path
   */
  def contextPath = session.map(_.contextPath).openOr("")

  /**
   * Finds a snippet by namae
   */
  def locateSnippet(name: String): Box[NodeSeq => NodeSeq] = {
    val snippet = if (name.indexOf(".") != -1) name.roboSplit("\\.") else name.roboSplit(":")
    NamedPF.applyBox(snippet, LiftRules.snippets.toList)
  }

  private object _currentSnippet extends RequestVar[Box[String]](Empty)

  private[http] def doSnippet[T](name: String)(f: => T): T = {
    val old = _currentSnippet.is
    try {
      _currentSnippet.set(Full(name))
      f
    } finally {
      _currentSnippet.set(old)
    }
  }

  def currentSnippet: Box[String] = _currentSnippet.is

  def locateMappedSnippet(name: String): Box[NodeSeq => NodeSeq] = Box(snippetMap.value.get(name))

  /**
   * Associates a name with a snippet function 'func'. This can be used to change a snippet
   * mapping on a per-session basis. For example, if we have a page that we want to change
   * behavior on based on query parameters, we could use mapSnippet to programmatically determine
   * which snippet function to use for a given snippet in the template. Our code would look like:
   *
   * <pre name="code" class="scala" >
   import _root_.scala.xml.{NodeSeq,Text}
   class SnipMap {
   def topSnippet (xhtml : NodeSeq) : NodeSeq = {
   if (S.param("showAll").isDefined) {
   S.mapSnippet("listing", listing)
   } else {
   S.mapSnippet("listing", { ignore => Text("") })
   }

   ...
   }

   def listing(xhtml : NodeSeq) : NodeSeq = {
   ...
   }
   </pre>
   *
   * Then, your template would simply look like:
   *
   * <pre name="code" class="scala" >
   &lt;lift:surround with="default" at="content"&gt;
   ...
   &lt;p&gt;&lt;lift:SnipMap.topSnippet /&gt;&lt;/p&gt;
   &lt;p&gt;&lt;lift:listing /&gt;&lt;/p&gt;
   &lt;/lift:surround&gt;
   * </pre>
   *
   * Snippets are processed in the order that they're defined in the
   * template, so if you want to use this approach make sure that
   * the snippet that defines the mapping comes before the snippet that
   * is being mapped.
   *
   * @param name The name of the snippet that you want to map (the part after "&lt;lift:").
   * @param func The snippet function to map to.
   */
  def mapSnippet(name: String, func: NodeSeq => NodeSeq) {snippetMap.value(name) = func}

  /**
   * Associates a name with a function impersonated by AFuncHolder. These are basically functions
   * that are executed when a request contains the 'name' request parameter.
   */
  def addFunctionMap(name: String, value: AFuncHolder) = _functionMap.value += (name -> value)

  private def booster(lst: List[String], func: String => Any): Unit = lst.foreach(v => func(v))

  /**
   * Decorates an URL with jsessionid parameter in case cookies are disabled from the container. Also
   * it appends general purpose parameters defined by LiftRules.urlDecorate
   */
  def encodeURL(url: String) = {
    URLRewriter.rewriteFunc map (_(url)) openOr url
  }

  /**
   * Build a handler for incoming JSON commands
   *
   * @param f - function returning a JsCmds
   * @return (JsonCall, JsCmd)
   */
  def buildJsonFunc(f: Any => JsCmd): (JsonCall, JsCmd) = buildJsonFunc(Empty, Empty, f)

  def buildJsonFunc(onError: JsCmd, f: Any => JsCmd): (JsonCall, JsCmd) =
  buildJsonFunc(Empty, Full(onError), f)

  /**
   * Build a handler for incoming JSON commands
   *
   * @param name -- the optional name of the command (placed in a comment for testing)
   *
   * @param f - function returning a JsCmds
   * @return (JsonCall, JsCmd)
   */
  def buildJsonFunc(name: Box[String], onError: Box[JsCmd], f: Any => JsCmd): (JsonCall, JsCmd) = {
    functionLifespan(true){
      val key = Helpers.nextFuncName

      def checkCmd(in: Any) = in match {
        case v2: _root_.scala.collection.Map[Any, _] if v2.isDefinedAt("command") =>
          // ugly code to avoid type erasure warning
          val v = v2.asInstanceOf[_root_.scala.collection.Map[String, Any]]
          JsonCmd(v("command").toString, v.get("target").
                  map {
              case null => null
              case x => x.toString
            } getOrElse(null),v.get("params").getOrElse(None), v)

        case v => v
      }

      def jsonCallback(in: List[String]): JsCmd = {
        in.firstOption.toList.flatMap{
          s =>
          val parsed = JSONParser.parse(s.trim).toList
          val cmds = parsed.map(checkCmd)
          val ret = cmds.map(f)
          ret
        }.foldLeft(JsCmds.Noop)(_ & _)
      }

      val onErrorFunc: String =
      onError.map(f => JsCmds.Run("function onError_"+key+"() {"+f.toJsCmd+"""
}

 """).toJsCmd) openOr ""

      val onErrorParam = onError.map(f => "onError_"+key) openOr "null"

      val af: AFuncHolder = jsonCallback _
      addFunctionMap(key, af)

      (JsonCall(key), JsCmds.Run(name.map(n => onErrorFunc +
                                          "/* JSON Func "+n+" $$ "+key+" */").openOr("") +
                                 "function "+key+"(obj) {lift_ajaxHandler(" +
                                 "'" + key + "='+ encodeURIComponent(" +
                                 LiftRules.jsArtifacts.
                                 jsonStringify(JE.JsRaw("obj")).
                                 toJsCmd +"), null,"+onErrorParam+");}"))
    }
  }

  /**
   * Returns the JsCmd that holds the notices markup
   *
   */
  private[http] def noticesToJsCmd: JsCmd = {

    val func: (() => List[NodeSeq], String, MetaData) => NodeSeq = (f, title, attr) => f() map (e => <li>{e}</li>) match {
      case Nil => Nil
      case list => <div>{title}<ul>{list}</ul></div> % attr
    }

    val f = noIdMessages _
    val xml = List((MsgsErrorMeta.get, f(S.errors), S.??("msg.error")),
                   (MsgsWarningMeta.get, f(S.warnings), S.??("msg.warning")),
                   (MsgsNoticeMeta.get, f(S.notices), S.??("msg.notice"))) flatMap {
      msg => msg._1 match {
        case Full(meta) => func(msg._2 _, meta.title openOr "", meta.cssClass.map(new UnprefixedAttribute("class", _, Null)) openOr Null)
        case _ => func(msg._2 _, msg._3, Null)
      }
    }

    val groupMessages = xml match {
      case Nil => JsCmds.Noop
      case _ => LiftRules.jsArtifacts.setHtml(LiftRules.noticesContainerId, xml)
    }

    val g = idMessages _
    List((MsgErrorMeta.get, g(S.errors)),
         (MsgWarningMeta.get, g(S.warnings)),
         (MsgNoticeMeta.get, g(S.notices))).foldLeft(groupMessages)((car, cdr) => cdr match {
        case (meta, m) => m.foldLeft(car)((left, r) =>
            left & LiftRules.jsArtifacts.setHtml(r._1, <span>{r._2 flatMap(node => node)}</span> %
                                                 (Box(meta.get(r._1)).map(new UnprefixedAttribute("class", _, Null)) openOr Null)))
      })
  }

  implicit def toLFunc(in: List[String] => Any): AFuncHolder = LFuncHolder(in, Empty)
  implicit def toNFunc(in: () => Any): AFuncHolder = NFuncHolder(in, Empty)
  implicit def stuff2ToUnpref(in: (Symbol, Any)): UnprefixedAttribute = new UnprefixedAttribute(in._1.name, Text(in._2.toString), Null)

  /**
   * Attaches to this uri and parameter that has function f associated with. When
   * this request is submitted to server the function will be executed and then
   * it is automatically cleaned up from functions caches.
   */
  def mapFuncToURI(uri: String, f : () => Unit): String = {
    session map (_ attachRedirectFunc(uri, Box.legacyNullTest(f))) openOr uri
  }

  /**
   * Abstrats a function that is executed on HTTP requests from client.
   */
  @serializable
  abstract class AFuncHolder {
    def owner: Box[String]
    def apply(in: List[String]): Any
    def duplicate(newOwner: String): AFuncHolder
    private[http] var lastSeen: Long = millis
    val sessionLife = functionLifespan_?
  }

  /**
   * Impersonates a function that will be called when uploading files
   */
  @serializable
  class BinFuncHolder(val func: FileParamHolder => Any, val owner: Box[String]) extends AFuncHolder {
    def apply(in: List[String]) {Log.error("You attempted to call a 'File Upload' function with a normal parameter.  Did you forget to 'enctype' to 'multipart/form-data'?")}
    def apply(in: FileParamHolder) = func(in)
    def duplicate(newOwner: String) = new BinFuncHolder(func, Full(newOwner))
  }

  object BinFuncHolder {
    def apply(func: FileParamHolder => Any) = new BinFuncHolder(func, Empty)
    def apply(func: FileParamHolder => Any, owner: Box[String]) = new BinFuncHolder(func, owner)
  }

  object SFuncHolder {
    def apply(func: String => Any) = new SFuncHolder(func, Empty)
    def apply(func: String => Any, owner: Box[String]) = new SFuncHolder(func, owner)
  }

  /**
   * Impersonates a function that is executed on HTTP requests from client. The function
   * takes a String as the only parameter and returns an Any.
   */
  @serializable
  class SFuncHolder(val func: String => Any, val owner: Box[String]) extends AFuncHolder {
    def this(func: String => Any) = this(func, Empty)
    def apply(in: List[String]): Any = in.firstOption.toList.map(func(_))
    def duplicate(newOwner: String) = new SFuncHolder(func, Full(newOwner))
  }

  object LFuncHolder {
    def apply(func: List[String] => Any) = new LFuncHolder(func, Empty)
    def apply(func: List[String] => Any, owner: Box[String]) = new LFuncHolder(func, owner)
  }

  /**
   * Impersonates a function that is executed on HTTP requests from client. The function
   * takes a List[String] as the only parameter and returns an Any.
   */
  @serializable
  class LFuncHolder(val func: List[String] => Any,val owner: Box[String]) extends AFuncHolder {
    def apply(in: List[String]): Any = func(in)
    def duplicate(newOwner: String) = new LFuncHolder(func, Full(newOwner))
  }

  object NFuncHolder {
    def apply(func: () => Any) = new NFuncHolder(func, Empty)
    def apply(func: () => Any, owner: Box[String]) = new NFuncHolder(func, owner)
  }

  /**
   * Impersonates a function that is executed on HTTP requests from client. The function
   * takes zero arguments and returns an Any.
   */
  @serializable
  class NFuncHolder(val func: () => Any,val owner: Box[String]) extends AFuncHolder {
    def apply(in: List[String]): Any = in.firstOption.toList.map(s => func())
    def duplicate(newOwner: String) = new NFuncHolder(func, Full(newOwner))
  }

  /**
   * Maps a function with an random generated and name
   */
  def fmapFunc[T](in: AFuncHolder)(f: String => T): T = //
  {
    val name = Helpers.nextFuncName
    addFunctionMap(name, in)
    f(name)
  }

  def render(xhtml:NodeSeq, httpRequest: HttpServletRequest): NodeSeq = {
    def doRender(session: LiftSession): NodeSeq =
    session.processSurroundAndInclude("external render", xhtml)

    if (inS.value) doRender(session.open_!)
    else {
      val req = Req(httpRequest, LiftRules.rewriteTable(httpRequest), System.nanoTime)
      val ses: LiftSession = SessionMaster.getSession(httpRequest, Empty) match {
        case Full(ret) =>
          ret.fixSessionTime()
          ret

        case _ =>
          val ret = LiftSession(httpRequest.getSession, req.contextPath,
                                req.headers)
          ret.fixSessionTime()
          SessionMaster.addSession(ret)
          ret
      }

      init(req, ses) {
        doRender(ses)
      }
    }
  }

  /**
   * Similar with addFunctionMap but also returns the name.
   *
   * Use fmapFunc(AFuncHolder)(String => T)
   */
  @deprecated
  def mapFunc(in: AFuncHolder): String = {
    mapFunc(Helpers.nextFuncName, in)
  }

  /**
   * Similar with addFunctionMap but also returns the name.
   *
   * Use fmapFunc(AFuncHolder)(String => T)
   */
  @deprecated
  def mapFunc(name: String, inf: AFuncHolder): String = {
    addFunctionMap(name, inf)
    name
  }


  /**
   * Returns all the HTTP parameters having 'n' name
   */
  def params(n: String): List[String] = request.flatMap(_.params.get(n)).openOr(Nil)
  /**
   * Returns the HTTP parameter having 'n' name
   */
  def param(n: String): Box[String] = request.flatMap(r => Box(r.param(n)))

  /**
   * Sets an ERROR notice as a plain text
   */
  def error(n: String) {error(Text(n))}
  /**
   * Sets an ERROR notice as an XML sequence
   */
  def error(n: NodeSeq) {p_notice.is += (NoticeType.Error, n,  Empty)}
  /**
   * Sets an ERROR notice as an XML sequence and associates it with an id
   */
  def error(id:String, n: NodeSeq) {p_notice.is += (NoticeType.Error, n,  Full(id))}
  /**
   * Sets an ERROR notice as plain text and associates it with an id
   */
  def error(id:String, n: String) {error(id, Text(n))}
  /**
   * Sets an NOTICE notice as plain text
   */
  def notice(n: String) {notice(Text(n))}
  /**
   * Sets an NOTICE notice as an XML sequence
   */
  def notice(n: NodeSeq) {p_notice.is += (NoticeType.Notice, n, Empty)}
  /**
   * Sets an NOTICE notice as and XML sequence and associates it with an id
   */
  def notice(id:String, n: NodeSeq) {p_notice.is += (NoticeType.Notice, n,  Full(id))}
  /**
   * Sets an NOTICE notice as plai text and associates it with an id
   */
  def notice(id:String, n: String) {notice(id, Text(n))}
  /**
   * Sets an WARNING notice as plain text
   */
  def warning(n: String) {warning(Text(n))}
  /**
   * Sets an WARNING notice as an XML sequence
   */
  def warning(n: NodeSeq) {p_notice += (NoticeType.Warning, n, Empty)}
  /**
   * Sets an WARNING notice as an XML sequence and associates it with an id
   */
  def warning(id:String, n: NodeSeq) {p_notice += (NoticeType.Warning, n,  Full(id))}
  /**
   * Sets an WARNING notice as plain text and associates it with an id
   */
  def warning(id:String, n: String) {warning(id, Text(n))}

  /**
   * Sets an ERROR notices from a List[FieldError]
   */
  def error(vi: List[FieldError]) {p_notice ++= vi.map{i => (NoticeType.Error, i.msg, i.field.uniqueFieldId )}}


  private [http] def message(msg: String, notice: NoticeType.Value) { message(Text(msg), notice)}
  private [http] def message(msg: NodeSeq, notice: NoticeType.Value) { p_notice += (notice, msg, Empty)}
  private [http] def messagesFromList(list: List[(NoticeType.Value, NodeSeq, Box[String])]) { list foreach ( p_notice += _) }

  /**
   * Returns the current notices
   */
  def getNotices: List[(NoticeType.Value, NodeSeq, Box[String])] = p_notice.toList

  /**
   * Returns only ERROR notices
   */
  def errors: List[(NodeSeq, Box[String])] = List(oldNotices.is, p_notice.is).flatMap(_.filter(_._1 == NoticeType.Error).map(n => (n._2, n._3)))
  /**
   * Returns only NOTICE notices
   */
  def notices: List[(NodeSeq, Box[String])] = List(oldNotices.is, p_notice.is).flatMap(_.filter(_._1 == NoticeType.Notice).map(n => (n._2, n._3)))
  /**
   * Returns only WARNING notices
   */
  def warnings: List[(NodeSeq, Box[String])] = List(oldNotices.is, p_notice.is).flatMap(_.filter(_._1 == NoticeType.Warning).map(n => (n._2, n._3)))
  /**
   * Clears up the notices
   */
  def clearCurrentNotices {p_notice.is.clear}

  /**
   * Returns the messages provided by list function that are associated with id
   *
   * @param id - the lookup id
   * @param f - the function that returns the messages
   */
  def messagesById(id: String)(f: => List[(NodeSeq, Box[String])]): List[NodeSeq] = f filter( _._2 map (_ equals id ) openOr false) map(_._1)

  /**
   *  Returns the messages that are not associated with any id
   *
   * @param f - the function that returns the messages
   */
  def noIdMessages(f: => List[(NodeSeq, Box[String])]): List[NodeSeq] = f filter( _._2 isEmpty) map (_._1)

  /**
   * Returns the messages that are associated with any id.
   * Messages associated with the same id will be enlisted.
   *
   * @param f - the function that returns the messages
   */
  def idMessages(f: => List[(NodeSeq, Box[String])]):List[(String, List[NodeSeq])] = {
    val res = new HashMap[String, List[NodeSeq]]
    f filter(  _._2.isEmpty == false) foreach (_ match {
        case (node, id) => val key = id open_!; res += (key -> (res.getOrElseUpdate(key, Nil) ::: List(node)))
      })

    res toList
  }

  implicit def tuple2FieldError(t: (FieldIdentifier, NodeSeq)) = FieldError(t._1, t._2)

}

/**
 * Defines the notices types
 */
@serializable
object NoticeType extends Enumeration {
  val Notice, Warning, Error = Value
}

/**
 * Used to handles JSON requests
 */
abstract class JsonHandler {
  private val name = "_lift_json_"+getClass.getName
  private def handlers: (JsonCall, JsCmd) =
  S.session.map(s => s.get[Any](name) match {
      case Full((x: JsonCall, y: JsCmd)) =>  (x, y)

      case _ =>
        val ret: (JsonCall, JsCmd) = S.buildJsonFunc(this.apply)
        s.set(name, ret)
        ret
    }
  ).openOr( (JsonCall(""), JsCmds.Noop) )

  def call: JsonCall = handlers._1

  def jsCmd: JsCmd = handlers._2

  def apply(in: Any): JsCmd
}

/**
 * Impersonates a JSON command
 */
case class JsonCmd(command: String, target: String, params: Any,
                   all: _root_.scala.collection.Map[String, Any])

/**
 * Holds information about a response
 */
class ResponseInfoHolder {
  var headers: Map[String, String] = Map.empty
  private var _docType: Box[String] = Empty
  private var _setDocType = false

  def docType = _docType
  def docType_=(in: Box[String]) {
    _docType = in
    _setDocType = true
  }

  def overrodeDocType = _setDocType
}

/**
 * Defines the association of this reference with an markup tag ID
 */
trait FieldIdentifier {
  def uniqueFieldId: Box[String] = Empty
}

/**
 * Associate a FieldIdentifier with an NodeSeq
 */
case class FieldError(field : FieldIdentifier, msg : NodeSeq) {
  override def toString = field.uniqueFieldId + " : " + msg
}

