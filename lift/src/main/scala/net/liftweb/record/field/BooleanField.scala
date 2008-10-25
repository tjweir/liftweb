/*
 * Copyright 2007-2008 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.liftweb.record.field

import scala.xml._
import net.liftweb.util._
import Helpers._
import net.liftweb.http.{S, FieldError}
import S._

class BooleanField[OwnerType <: Record[OwnerType]](rec: OwnerType) extends Field[Boolean, OwnerType] {
  override def owner = rec

  def this(rec: OwnerType, value: Boolean) = {
    this(rec)
    set(value)
  }

  /**
   * Sets the field value from an Any
   */
  override def setFromAny(in: Any): Can[Boolean] = {
    in match {
      case b: Boolean => Full(this.set(b))
      case (b: Boolean) :: _ => Full(this.set(b))
      case Some(b: Boolean) => Full(this.set(b))
      case Full(b: Boolean) => Full(this.set(b))
      case Empty | Failure(_, _, _) | None => Full(this.set(false))
      case (s: String) :: _ => Full(this.set(toBoolean(s)))
      case null => Full(this.set(false))
      case s: String => Full(this.set(toBoolean(s)))
      case o => Full(this.set(toBoolean(o)))
    }
  }

   override def setFromString(s: String) : Can[Boolean] = {
    try{
      Full(set(java.lang.Boolean.parseBoolean(s)));
    } catch {
      case e: Exception => Empty
    }
  }

  override def toForm = {
    var el = <input type="checkbox"
      name={S.mapFunc(SFuncHolder(this.setFromAny(_)))}
      value={value.toString}
      tabindex={tabIndex toString}/>;

    uniqueFieldId match {
      case Full(id) =>
        <div id={id+"_holder"}><div><label for={id+"_field"}>{displayName}</label></div>{el % ("id" -> (id+"_field"))}<lift:msg id={id}/></div>
      case _ => <div>{el}</div>
    }

  }

  def asXHtml: NodeSeq = {
    var el = <input type="checkbox"
      name={S.mapFunc(SFuncHolder(this.setFromAny(_)))}
      value={value.toString}
      tabindex={tabIndex toString}/>;

    uniqueFieldId match {
      case Full(id) =>  el % ("id" -> (id+"_field"))
      case _ => el
    }
  }


  override def defaultValue = false

}

import java.sql.{ResultSet, Types}
import net.liftweb.mapper.{DriverType}

/**
 * An int field holding DB related logic
 */
abstract class DBBooleanField[OwnerType <: DBRecord[OwnerType]](rec: OwnerType) extends BooleanField[OwnerType](rec) {

  def targetSQLType = Types.BOOLEAN

  /**
   * Given the driver type, return the string required to create the column in the database
   */
  def fieldCreatorString(dbType: DriverType, colName: String): String = colName + " " + dbType.enumColumnType

  def jdbcFriendly(field : String) : Boolean = value

}
