/*
 * Part of NDLA audio_api.
 * Copyright (C) 2016 NDLA
 *
 * See LICENSE
 *
 */

package no.ndla.audioapi.model.domain

import no.ndla.audioapi.AudioApiProperties
import org.json4s.FieldSerializer
import org.json4s.FieldSerializer._
import org.json4s.native.Serialization._
import org.scalatra.swagger.annotations._
import org.scalatra.swagger.runtime.annotations.ApiModelProperty
import scalikejdbc._

import scala.annotation.meta.field

case class AudioMetaInformation(id: Option[Long], titles: Seq[Title], filePaths: Seq[Audio],
                                copyright: Copyright, tags: Seq[Tag])
case class Title(title: String, language: Option[String])
case class Audio(filePath: String, mimeType: String, fileSize: Long, language: Option[String])
case class Copyright(license: String, origin: Option[String], authors: Seq[Author])
case class Author(`type`: String, name: String)
case class Tag(tags: Seq[String], language: Option[String])

object AudioMetaInformation extends SQLSyntaxSupport[AudioMetaInformation] {
  implicit val formats = org.json4s.DefaultFormats
  override val tableName = "audiodata"
  override val schemaName = Some(AudioApiProperties.MetaSchema)

  def apply(au: SyntaxProvider[AudioMetaInformation])(rs:WrappedResultSet): AudioMetaInformation = apply(au.resultName)(rs)
  def apply(au: ResultName[AudioMetaInformation])(rs: WrappedResultSet): AudioMetaInformation = {
    val meta = read[AudioMetaInformation](rs.string(au.c("document")))
    AudioMetaInformation(Some(rs.long(au.c("id"))), meta.titles, meta.filePaths, meta.copyright, meta.tags)
  }

  val JSonSerializer = FieldSerializer[AudioMetaInformation](ignore("id") orElse ignore("external_id"))
}
