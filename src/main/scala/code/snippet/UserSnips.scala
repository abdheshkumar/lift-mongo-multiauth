package code
package snippet

import config.Site
import lib.{Gravatar, AppHelpers}
import model.{User, LoginCredentials}
import scala.xml._
import net.liftweb._
import common._
import http.{DispatchSnippet, S, SHtml, StatefulSnippet}
import util._
import Helpers._
import omniauth.Omniauth

sealed trait UserSnippet extends DispatchSnippet with AppHelpers with Loggable {

  def dispatch = {
    case "header" => header
    case "gravatar" => gravatar
    case "name" => name
    case "username" => username
    case "title" => title
  }

  protected def user: Box[User]

  protected def serve(snip: User => NodeSeq): NodeSeq =
    (for {
      u <- user ?~ "User not found"
    } yield {
      snip(u)
    }): NodeSeq

  def header(xhtml: NodeSeq): NodeSeq = serve { user =>
    <div id="user-header">
      {gravatar(xhtml)}
      <h3>{name(xhtml)}</h3>
    </div>
  }
  
  def gravatar(xhtml: NodeSeq): NodeSeq = {
    val size = S.attr("size").map(toInt) openOr Gravatar.defaultSize.vend

    serve { user =>
      Gravatar.imgTag(user.email.is, size)
    }
  }

  def username(xhtml: NodeSeq): NodeSeq = serve { user =>
    Text(user.username.is)
  }

  def name(xhtml: NodeSeq): NodeSeq = serve { user =>
    if (user.name.is.length > 0)
      Text("%s (%s)".format(user.name.is, user.username.is))
    else
      Text(user.username.is)
  }

  def title(xhtml: NodeSeq): NodeSeq = serve { user =>
    <lift:head>
      <title lift="Menu.title">{"lift-mongo-app: %*% - "+user.username.is}</title>
    </lift:head>
  }
}

object CurrentUser extends UserSnippet {
  override protected def user = User.currentUser
}

object ProfileLocUser extends UserSnippet {
  override def dispatch = super.dispatch orElse {
    case "profile" => profile
  }

  override protected def user = Site.profileLoc.currentValue

  import java.text.SimpleDateFormat

  val df = new SimpleDateFormat("MMM d, yyyy")

  def profile(xhtml: NodeSeq): NodeSeq = serve { user =>
    val editLink: NodeSeq =
      if (User.currentUser.filter(_.id.is == user.id.is).isDefined)
        <a href={Site.editProfile.url} class="btn info">Edit Your Profile</a>
      else
        NodeSeq.Empty

    val cssSel =
      "#id_avatar *" #> Gravatar.imgTag(user.email.is) &
      "#id_name *" #> <h3>{user.name.is}</h3> &
      "#id_location *" #> user.location.is &
      "#id_whencreated" #> df.format(user.whenCreated.toDate).toString &
      "#id_bio *" #> user.bio.is &
      "#id_editlink *" #> editLink

    cssSel.apply(xhtml)
  }
}

class LogUserOauth {

  def logUserIn(html: NodeSeq): NodeSeq = {

    Omniauth.currentAuth match {
      case Full(omni) => ({
        val uid = omni.uid
        
        User.findByUID(uid) match {
        case Full(user)  =>
          User.logUserIn(user, true)
          User.createExtSession(user.id.is)
          S.seeOther(Site.home.url)
        case _ => {
            val user = User.createRecord
            		.email(omni.email)
            		.uid(omni.uid)
            		.name(omni.name)
            		.provider(omni.provider)
            		.oauthToken(omni.token)
            		.secret(omni.secret)
            		.username(omni.nickName).save
            User.logUserIn(user, true)
            User.createExtSession(user.id.is)
            S.seeOther(Site.home.url)
            html
        }
      }
      })
      case _ => html
    }
  }
}

class UserLogin extends StatefulSnippet with Loggable {
  def dispatch = { case "render" => render }

  // form vars
  private var password = ""
  private var hasPassword = true
  private var remember = true


  def render = {
    "#id_email [value]" #> User.loginCredentials.is.email &
    "#id_password" #> SHtml.password(password, password = _) &
    "name=remember" #> SHtml.checkbox(remember, remember = _) &
    "#id_submit" #> SHtml.onSubmitUnit(process)
  }

  private def process(): Unit = S.param("email").map(e => {
    val email = e.toLowerCase.trim
    // save the email and remember entered in the session var
    User.loginCredentials(LoginCredentials(email, remember))

    if (hasPassword && email.length > 0 && password.length > 0) {
      User.findByEmail(email) match {
        case Full(user) if (user.password.isMatch(password)) =>
          User.logUserIn(user, true)
          if (remember) User.createExtSession(user.id.is)
          S.seeOther(Site.home.url)
        case _ => S.error("Invalid credentials.")
      }
    }
    else if (hasPassword && email.length <= 0 && password.length > 0)
      S.error("Please enter an email.")
    else if (hasPassword && password.length <= 0 && email.length > 0)
      S.error("Please enter a password.")
    else if (hasPassword)
      S.error("Please enter an email and password.")
    else if (email.length > 0) {
      // see if email exists in the database
      User.findByEmail(email) match {
        case Full(user) => {
          User.sendLoginToken(user)
          User.loginCredentials.remove()
          S.notice("An email has been sent to you with instructions for accessing your account.")
          S.seeOther(Site.home.url)
        }
        case _ => S.seeOther(Site.register.url)
      }
    }
    else
      S.error("Please enter an email address")
  }) openOr S.error("Please enter an email address")

}

object UserTopbar {
  def render = {
    User.currentUser match {

      case Full(user) =>
        <ul class="nav pull-right" id="user">
          <li class="dropdown" >
            <a href="#" class="dropdown-toggle" data-toggle="dropdown">
              {Gravatar.imgTag(user.email.is, 20)}
              <span>{user.username.is}</span>
            </a>
            <ul class="dropdown-menu">
              <li><a href={"/user/%s".format(user.username.is)}>Profile</a></li>
              <li><lift:Menu.item name="Account" donthide="true" linktoself="true">Settings</lift:Menu.item></li>
              <li><lift:Menu.item name="About" donthide="true" linktoself="true">Help</lift:Menu.item></li>
              <li class="divider"></li>
              <li><lift:Menu.item name="Logout" donthide="true" linktoself="true">Log Out</lift:Menu.item></li>
            </ul>
          </li>
        </ul>
      case _ if (S.request.flatMap(_.location).map(_.name).filterNot(it => List("Login", "Register","Signup").contains(it)).isDefined) =>
        <div>
      	<form action="/signup" class="navbar-form pull-right">
          <button class="btn btn-warning">Sign Up</button>
        </form>
      	<form action="/login" class="navbar-form pull-right">
          <button class="btn btn-primary ">Sign In</button>
        </form>
      	</div>
      case _ => NodeSeq.Empty
    }
  }
}
