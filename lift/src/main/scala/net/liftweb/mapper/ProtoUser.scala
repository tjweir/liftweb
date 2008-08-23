package net.liftweb.mapper

/*
 * Copyright 2006-2008 WorldWide Conferencing, LLC
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

import net.liftweb.mapper._
import net.liftweb.http._
import js._
import JsCmds._
import scala.xml.{NodeSeq, Node, Group}
import scala.xml.transform._
import net.liftweb.sitemap._
import net.liftweb.sitemap.Loc._
import net.liftweb.util.Helpers._
import net.liftweb.util._
import net.liftweb.util.Mailer._
import S._

trait ProtoUser[T <: ProtoUser[T]] extends KeyedMapper[Long, T] with UserIdAsString {
  self: T =>

  override def primaryKeyField = id

  // the primary key for the database
  object id extends MappedLongIndex(this)
  
  def userIdAsString: String = id.is.toString

  // First Name
  object firstName extends MappedString(this, 32)
  
  // Last Name
  object lastName extends MappedString(this, 32)
  
  // Email
  object email extends MappedEmail(this, 48) {
    override def dbIndexed_? = true
    override def validations = valUnique(S.??("unique.email.address")) _ :: super.validations
  }
  
  // Password
  object password extends MappedPassword[T](this)
  
  
  object superUser extends MappedBoolean(this) {
    override def defaultValue = false
  }
  
  def niceName: String = (firstName.is, lastName.is, email.is) match {
    case (f, l, e) if f.length > 1 && l.length > 1 => f+" "+l+" ("+e+")"
    case (f, _, e) if f.length > 1 => f+" ("+e+")"
    case (_, l, e) if l.length > 1 => l+" ("+e+")"
    case (_, _, e) => e
  }
  
  def shortName: String = (firstName.is, lastName.is) match {
    case (f, l) if f.length > 1 && l.length > 1 => f+" "+l
    case (f, _) if f.length > 1 => f
    case (_, l) if l.length > 1 => l
    case _ => email.is
  }
  
  def niceNameWEmailLink = <a href={"mailto:"+email.is}>{niceName}</a>
}

trait MetaMegaProtoUser[ModelType <: MegaProtoUser[ModelType]] extends KeyedMetaMapper[Long, ModelType] {
  self: ModelType =>
  
  def signupFields: List[BaseOwnedMappedField[ModelType]] = firstName :: lastName :: email :: locale :: timezone :: password :: Nil
  
  override def fieldOrder: List[BaseOwnedMappedField[ModelType]] = firstName :: lastName :: email :: locale :: timezone :: password :: Nil
  
  /**
   * If the
   */
  def screenWrap: Can[Node] = Empty
  
  val basePath: List[String] = "user_mgt" :: Nil
  def signUpSuffix = "sign_up"
  lazy val signUpPath = thePath(signUpSuffix)
  def loginSuffix = "login"
  lazy val loginPath = thePath(loginSuffix)
  def lostPasswordSuffix = "lost_password"
  lazy val lostPasswordPath = thePath(lostPasswordSuffix)
  def passwordResetSuffix = "reset_password"
  lazy val passwordResetPath = thePath(passwordResetSuffix)
  def changePasswordSuffix = "change_password"
  lazy val changePasswordPath = thePath(changePasswordSuffix)
  def logoutSuffix = "logout"
  lazy val logoutPath = thePath(logoutSuffix)
  def editSuffix = "edit"
  lazy val editPath = thePath(editSuffix)
  def validateUserSuffix = "validate_user"
  lazy val validateUserPath = thePath(validateUserSuffix)
  
  def homePage = "/"


  
  case class MenuItem(name: String, path: List[String], loggedIn: Boolean) {
    lazy val endOfPath = path.last
    lazy val pathStr: String = path.mkString("/", "/", "")
    lazy val display = name match {
      case null | "" => false
      case _ => true
    }
  }
  
  def thePath(end: String): List[String] = basePath ::: List(end)
  
  /**
   * Return the URL of the "login" page
   */
  def loginPageURL = loginPath.mkString("/","/", "")
  
  def notLoggedIn_? = !loggedIn_? 
  
  lazy val testLogginIn = If(loggedIn_? _, S.??("must.be.logged.in")) ;
  
  /**
   * The menu item for login (make this "Empty" to disable)
   */
  def loginMenuLoc: Can[Menu] = {
    Full(Menu(Loc("Login", loginPath, S.??("login"),
                  If(notLoggedIn_? _, S.??("already.logged.in")))))
  }
  
  /**
   * The menu item for logout (make this "Empty" to disable)
   */
  def logoutMenuLoc: Can[Menu] =
  Full(Menu(Loc("Logout", logoutPath, S.??("logout"), testLogginIn)))
  
  /**
   * The menu item for creating the user/sign up (make this "Empty" to disable)
   */
  def createUserMenuLoc: Can[Menu] =
  Full(Menu(Loc("CreateUser", signUpPath,
                S.??("sign.up"), If(notLoggedIn_? _, S.??("logout.first")))))
  
  /**
   * The menu item for lost password (make this "Empty" to disable)
   */
  def lostPasswordMenuLoc: Can[Menu] =
  Full(Menu(Loc("LostPassword", lostPasswordPath,
                S.??("lost.password"),
                If(notLoggedIn_? _, S.??("logout.first"))))) // not logged in
  
  /**
   * The menu item for resetting the password (make this "Empty" to disable)
   */
  def resetPasswordMenuLoc: Can[Menu] =
  Full(Menu(Loc("ResetPassword", (passwordResetPath, true),
                S.??("reset.password"), Hidden,
                If(notLoggedIn_? _,
                   S.??("logout.first"))))) //not Logged in
  
  /**
   * The menu item for editing the user (make this "Empty" to disable)
   */
  def editUserMenuLoc: Can[Menu] =
  Full(Menu(Loc("EditUser", editPath, S.??("edit.user"), testLogginIn)))
  
  /**
   * The menu item for changing password (make this "Empty" to disable)
   */
  def changePasswordMenuLoc: Can[Menu] =
  Full(Menu(Loc("ChangePassword", changePasswordPath,
                S.??("change.password"), testLogginIn)))
  
  /**
   * The menu item for validating a user (make this "Empty" to disable)
   */
  def validateUserMenuLoc: Can[Menu] =
  Full(Menu(Loc("ValidateUser", (validateUserPath, true),
                S.??("validate.user"), Hidden,
                If(notLoggedIn_? _, S.??("logout.first")))))
  
  lazy val sitemap: List[Menu] =
  List(loginMenuLoc, logoutMenuLoc, createUserMenuLoc,
       lostPasswordMenuLoc, resetPasswordMenuLoc,
       editUserMenuLoc, changePasswordMenuLoc,
       validateUserMenuLoc).flatten(a => a)
  
  
  def skipEmailValidation = false
  
  def userMenu: List[Node] = {
    val li = loggedIn_?
    ItemList.
    filter(i => i.display && i.loggedIn == li).
    map(i => (<a href={i.pathStr}>{i.name}</a>))
  }
  
  lazy val ItemList: List[MenuItem] = MenuItem(S.??("sign.up"), signUpPath, false) ::
  MenuItem(S.??("log.in"), loginPath, false) ::
  MenuItem(S.??("lost.password"), lostPasswordPath, false) ::
  MenuItem("", passwordResetPath, false) ::
  MenuItem(S.??("change.password"), changePasswordPath, true) ::
  MenuItem(S.??("log.out"), logoutPath, true) ::
  MenuItem(S.??("edit.profile"), editPath, true) ::
  MenuItem("", validateUserPath, false) :: Nil
  
  def templates: LiftRules.TemplatePf = {
    case RequestState(path, "", _)
    if path.startsWith(signUpPath) && testLoggedIn(signUpSuffix) => () => signup
    
    case RequestState(path, "", _)
      if path.startsWith(loginPath) && testLoggedIn(loginSuffix) => () => login
    
    case RequestState(path, "", _)
      if path.startsWith(lostPasswordPath) &&
      testLoggedIn(lostPasswordSuffix) => () => lostPassword
    
    case RequestState(path, "", _)
      if path.startsWith(passwordResetPath) &&
      testLoggedIn(passwordResetSuffix) =>
      () => passwordReset(path.drop(passwordResetPath.length).head)
    
    case RequestState(path, "", _)
      if path.startsWith(changePasswordPath) &&
      testLoggedIn(changePasswordSuffix) => () => changePassword
    
    case RequestState(path, "", _)
      if path.startsWith(logoutPath) &&
      testLoggedIn(logoutSuffix) => () => logout
    
    case RequestState(path, "", _)
      if path.startsWith(editPath) && testLoggedIn(editSuffix) => () => edit
    
    case RequestState(path, "", _)
      if path.startsWith(validateUserPath) &&
      testLoggedIn(validateUserSuffix) =>
      () => validateUser(path.drop(validateUserPath.length).head)
  }
  
  def requestLoans: List[LoanWrapper] = Nil // List(curUser)
  
  var onLogIn: List[ModelType => Unit] = Nil

  var onLogOut: List[Can[ModelType] => Unit] = Nil
   
  def loggedIn_? : Boolean = currentUserId.isDefined

  def logUserIdIn(id: String) {
    curUser.remove()
    curUserId(Full(id))
  }
  def logUserIn(who: ModelType) {
    curUser.remove()
    curUserId(Full(who.id.toString))
    onLogIn.foreach(_(who))
  }

  def logoutCurrentUser = logUserOut()

  def logUserOut() {
    onLogOut.foreach(_(curUser))
    curUserId.remove()
    curUser.remove()
    S.request.foreach(_.request.getSession.invalidate)
  }

  private object curUserId extends SessionVar[Can[String]](Empty)

  def currentUserId: Can[String] = curUserId.is

  private object curUser extends RequestVar[Can[ModelType]](currentUserId.flatMap(id => getSingleton.find(id)))


  def currentUser: Can[ModelType] = curUser.is

  def signupXhtml(user: ModelType) = {
    (<form method="POST" action={S.uri}><table><tr><td
              colspan="2">Sign Up</td></tr>
          {localForm(user, false)}
          <tr><td>&nbsp;</td><td><user:submit/></td></tr>
                                        </table></form>)
  }
  
  
  def signupMailBody(user: ModelType, validationLink: String) = {
    (<html>
        <head>
          <title>Sign Up Confirmation</title>
        </head>
        <body>
          <p>Dear {user.firstName},
            <br/>
            <br/>
            Click on this link to complete signup
            <br/><a href={validationLink}>{validationLink}</a>
            <br/>
            <br/>
            Thanks
          </p>
        </body>
     </html>)
  }
  
  def signupMailSubject = S.??("sign.up.confirmation")
  
  def sendValidationEmail(user: ModelType) {
    val resetLink = S.hostAndPath+"/"+validateUserPath.mkString("/")+
    "/"+user.uniqueId
    
    val email: String = user.email
    
    val msgXml = signupMailBody(user, resetLink)
    
    Mailer.sendMail(From(emailFrom),Subject(signupMailSubject),
                    (To(user.email) :: xmlToMailBodyType(msgXml) ::
                     (bccEmail.toList.map(BCC(_)))) :_* )
  }
  
  def signup = {
    val theUser: ModelType = create
    val theName = signUpPath.mkString("")
    
    def testSignup() {
      theUser.validate match {
        case Nil => S.removeSessionTemplater(theName)
          theUser.validated(skipEmailValidation).uniqueId.reset()
          theUser.save
          if (!skipEmailValidation) {
            sendValidationEmail(theUser)
            S.notice(S.??("sign.up.message"))
          } else {
            S.notice(S.??("welcome"))
            logUserIn(theUser)
          }
        
          S.redirectTo(homePage)
        
        case xs => S.error(xs)
      }
    }
    
    def innerSignup = bind("user", 
                           signupXhtml(theUser),
                           "submit" -> SHtml.submit(S.??("sign.up"), testSignup()))
    
    S.addSessionTemplater(theName, {
        case RequestState(path, "", _)
          if path.startsWith(signUpPath) && testLoggedIn(signUpSuffix) =>  () => innerSignup
      })
    innerSignup
  }
  
  def emailFrom = "noreply@"+S.hostName
  
  def bccEmail: Can[String] = Empty
  
  def testLoggedIn(page: String): Boolean =
  ItemList.filter(_.endOfPath == page) match {
    case x :: xs if x.loggedIn == loggedIn_? => true
    case _ => false
  }
  
  
  def validateUser(id: String): NodeSeq = getSingleton.find(By(uniqueId, id)) match {
    case Full(user) if !user.validated =>
      user.validated(true).uniqueId.reset().save
      S.notice(S.??("account.validated"))
      logUserIn(user)
      S.redirectTo(homePage)
    
    case _ => S.error(S.??("invalid.validation.link")); S.redirectTo(homePage)
  }
  
def loginXhtml = {
  (<form method="POST" action={S.uri}><table><tr><td
            colspan="2">{S.??("log.in")}</td></tr>
        <tr><td>{S.??("email.address")}</td><td><user:email /></td></tr>
        <tr><td>{S.??("password")}</td><td><user:password /></td></tr>
        <tr><td><a href={lostPasswordPath.mkString("/", "/", "")}
              >{S.??("recover.password")}</a></td><td><user:submit /></td></tr></table>
   </form>)
}
  
  def login = {
    if (S.post_?) {
      S.param("username").
      flatMap(username => getSingleton.find(By(email, username))) match {
        case Full(user) if user.validated &&
          user.password.match_?(S.param("password").openOr("*")) =>
          logUserIn(user); S.notice(S.??("logged.in")); S.redirectTo(homePage)
        
        case Full(user) if !user.validated =>
          S.error(S.??("account.validation.error"))
        
        case _ => S.error(S.??("invalid.credentials"))
      }
    }
    
    bind("user", loginXhtml,
         "email" -> (FocusOnLoad(<input type="text" name="username"/>)),
         "password" -> (<input type="password" name="password"/>),
         "submit" -> (<input type="submit" value={S.??("log.in")}/>))
  }
  
  def lostPasswordXhtml = {
    (<form method="POST" action={S.uri}>
        <table><tr><td
              colspan="2">{S.??("enter.email")}</td></tr>
          <tr><td>{S.??("email.address")}</td><td><user:email /></td></tr>
          <tr><td>&nbsp;</td><td><user:submit /></td></tr>
        </table>
     </form>)
  }
  
  def passwordResetMailBody(user: ModelType, resetLink: String) = {
    (<html>
        <head>
          <title>{S.??("reset.password.confirmation")}</title>
        </head>
        <body>
          <p>{S.??("dear")} {user.firstName},
            <br/>
            <br/>
            {S.??("click.reset.link")}
            <br/><a href={resetLink}>{resetLink}</a>
            <br/>
            <br/>
            {S.??("thank.you")}
          </p>
        </body>
     </html>)
  }
  
  def passwordResetEmailSubject = S.??("reset.password.request")
  
  def sendPasswordReset(email: String) {
    getSingleton.find(By(this.email, email)) match {
      case Full(user) if user.validated =>
        user.uniqueId.reset().save
        val resetLink = S.hostAndPath+
        passwordResetPath.mkString("/", "/", "/")+user.uniqueId
      
        val email: String = user.email
      
        val msgXml = passwordResetMailBody(user, resetLink)
        Mailer.sendMail(From(emailFrom),Subject(passwordResetEmailSubject),
                        (To(user.email) :: xmlToMailBodyType(msgXml) ::
                         (bccEmail.toList.map(BCC(_)))) :_*)
      
        S.notice(S.??("password.reset.email.sent"))
        S.redirectTo(homePage)
      
      case Full(user) =>
        sendValidationEmail(user)
        S.notice(S.??("account.validation.resent"))
        S.redirectTo(homePage)
      
      case _ => S.error(S.??("email.address.not.found"))
    }
  }
  
  def lostPassword = {
    bind("user", lostPasswordXhtml, 
         "email" -> SHtml.text("", sendPasswordReset _),
         "submit" -> <input type="Submit" value={S.??("send.it")} />)
  }
  
  def passwordResetXhtml = {
    (<form method="POST" action={S.uri}>
        <table><tr><td colspan="2">{S.??("reset.your.password")}</td></tr>
          <tr><td>{S.??("enter.your.new.password")}</td><td><user:pwd/></td></tr>
          <tr><td>{S.??("repeat.your.new.password")}</td><td><user:pwd/></td></tr>
          <tr><td>&nbsp;</td><td><user:submit/></td></tr>
        </table>
     </form>)
  }
  
  def passwordReset(id: String) =
  getSingleton.find(By(uniqueId, id)) match {
    case Full(user) =>
      def finishSet() {
        user.validate match {
          case Nil => S.notice(S.??("password.changed"))
            user.save
            logUserIn(user); S.redirectTo(homePage)
        
          case xs => S.error(xs)
        }
      }
      user.uniqueId.reset().save
    
      bind("user", passwordResetXhtml,
           "pwd" -> SHtml.password_*("",(p: List[String]) =>
                                     user.password.setList(p)),
           "submit" -> SHtml.submit(S.??("set.password"), finishSet()))
    case _ => S.error(S.??("pasword.link.invalid")); S.redirectTo(homePage)
  }
  
  def changePasswordXhtml = {
    (<form method="POST" action={S.uri}>
        <table><tr><td colspan="2">{S.??("change.password")}</td></tr>
          <tr><td>{S.??("old.password")}</td><td><user:old_pwd /></td></tr>
          <tr><td>{S.??("new.password")}</td><td><user:new_pwd /></td></tr>
          <tr><td>{S.??("repeat.password")}</td><td><user:new_pwd /></td></tr>
          <tr><td>&nbsp;</td><td><user:submit /></td></tr>
        </table>
     </form>)
  }
  
  def changePassword = {
    val user = currentUser.open_! // we can do this because the logged in test has happened
    var oldPassword = ""
    var newPassword: List[String] = Nil
    
    def testAndSet() {
      if (!user.password.match_?(oldPassword)) S.error(S.??("wrong.old.password"))
      else {
        user.password.setFromAny(newPassword)
        user.validate match {
          case Nil => user.save; S.notice(S.??("pasword.changed")); S.redirectTo(homePage)
          case xs => S.error(xs)
        }
      }
    }
    
    bind("user", changePasswordXhtml,
         "old_pwd" -> SHtml.password("", oldPassword = _),
         "new_pwd" -> SHtml.password_*("", LFuncHolder(newPassword = _)),
         "submit" -> SHtml.submit(S.??("change"), testAndSet()))
  }
  
  def editXhtml(user: ModelType) = {
    (<form method="POST" action={S.uri}>
        <table><tr><td colspan="2">{S.??("edit")}</td></tr>
          {localForm(user, true)}
          <tr><td>&nbsp;</td><td><user:submit/></td></tr>
        </table>
     </form>)
  }
  
  def edit = {
    val theUser: ModelType = currentUser.open_! // we know we're logged in
    val theName = editPath.mkString("")
    
    def testEdit() {
      theUser.validate match {
        case Nil => S.removeSessionTemplater(theName)
          theUser.save
          S.notice(S.??("profle.updated"))
          S.redirectTo(homePage)
        
        case xs => S.error(xs)
      }
    }
    
    def innerEdit = bind("user", editXhtml(theUser), 
                         "submit" -> SHtml.submit(S.??("edit"), testEdit()))
    
    S.addSessionTemplater(theName, {
        case RequestState(path, "", _)
          if path.startsWith(editPath) && testLoggedIn(editSuffix) =>
          () => innerEdit})
    innerEdit
  }
  
  def logout = {
    logoutCurrentUser
    S.redirectTo(homePage)
  }
  
  private def localForm(user: ModelType, ignorePassword: Boolean): NodeSeq = {
    signupFields.
    map(fi => getSingleton.getActualBaseField(user, fi)).
    filter(f => !ignorePassword || (f match {
          case f: MappedPassword[ModelType] => false
          case _ => true
        })).
    flatMap(f =>
            f.toForm.toList.map(form =>
                                (<tr><td>{f.displayName}</td><td>{form}</td></tr>) ) )
  }
  
  implicit def nodeSeqToOption(in: NodeSeq): Can[NodeSeq] =
  screenWrap.map{
    theDoc =>
    val rw = new RewriteRule {
      override def transform(n: Node) = n match {
        case e @ (<bind />) if "lift" == e.prefix => in
        case _ => n
      }
    }
    (new RuleTransformer(rw))(theDoc)
  } or Full(in)
}

trait MegaProtoUser[T <: MegaProtoUser[T]] extends ProtoUser[T] {
  self: T =>
  object uniqueId extends MappedUniqueId(this, 32) {
    override def dbIndexed_? = true
    override def writePermission_?  = true
  }
  
  object validated extends MappedBoolean[T](this) {
    override def defaultValue = false
  }
  
  object locale extends MappedLocale[T](this)
  
  object timezone extends MappedTimeZone[T](this)
  
}
