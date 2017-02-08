/*
 * Part of NDLA audio_api.
 * Copyright (C) 2016 NDLA
 *
 * See LICENSE
 *
 */

package no.ndla.audioapi.repository

import com.typesafe.scalalogging.LazyLogging
import no.ndla.audioapi.AudioApiProperties
import no.ndla.audioapi.integration.DataSource
import no.ndla.audioapi.model.domain.AudioMetaInformation
import org.json4s.native.Serialization._
import org.postgresql.util.PGobject
import scalikejdbc.{DBSession, ReadOnlyAutoSession, _}

import scala.util.Try


trait AudioRepository {
  this: DataSource =>
  val audioRepository: AudioRepository

  class AudioRepository extends LazyLogging {
    implicit val formats = org.json4s.DefaultFormats + AudioMetaInformation.JSonSerializer

    ConnectionPool.singleton(new DataSourceConnectionPool(dataSource))

    def withId(id: Long): Option[AudioMetaInformation] = {
      DB readOnly { implicit session =>
        audioMetaInformationWhere(sqls"au.id = $id")
      }
    }

    def withExternalId(externalId: String): Option[AudioMetaInformation] = {
      DB readOnly { implicit session =>
        audioMetaInformationWhere(sqls"au.external_id = $externalId")
      }
    }

    def insert(audioMetaInformation: AudioMetaInformation)(implicit session: DBSession = AutoSession): AudioMetaInformation = {
      val dataObject = new PGobject()
      dataObject.setType("jsonb")
      dataObject.setValue(write(audioMetaInformation))

      val audioId = sql"insert into audiodata (document) values (${dataObject})".updateAndReturnGeneratedKey.apply
      audioMetaInformation.copy(id = Some(audioId))
    }

    def insertFromImport(audioMetaInformation: AudioMetaInformation, externalId: String): AudioMetaInformation = {
      val dataObject = new PGobject()
      dataObject.setType("jsonb")
      dataObject.setValue(write(audioMetaInformation))

      DB localTx { implicit session =>
        val audioId = sql"insert into audiodata(external_id, document) values(${externalId}, ${dataObject})".updateAndReturnGeneratedKey.apply
        audioMetaInformation.copy(id = Some(audioId))
      }
    }

    def update(audioMetaInformation: AudioMetaInformation, id: Long): AudioMetaInformation = {
      val dataObject = new PGobject()
      dataObject.setType("jsonb")
      dataObject.setValue(write(audioMetaInformation))

      DB localTx { implicit session =>
        sql"update audiodata set document = ${dataObject} where id = ${id}".update.apply
        audioMetaInformation.copy(id = Some(id))
      }
    }

    def numElements: Int = {
      DB readOnly { implicit session =>
        sql"select count(*) from audiodata".map(rs => {
          rs.int("count")
        }).list.first().apply() match {
          case Some(count) => count
          case None => 0
        }
      }
    }

    def minMaxId: (Long, Long) = {
      DB readOnly { implicit session =>
        sql"select coalesce(MIN(id),0) as mi, coalesce(MAX(id),0) as ma from audiodata".map(rs => {
          (rs.long("mi"), rs.long("ma"))
        }).single().apply() match {
          case Some(minmax) => minmax
          case None => (0L, 0L)
        }
      }
    }

    def applyToAllNew(func: List[AudioMetaInformation] => Try[Unit]) = {
      val au = AudioMetaInformation.syntax("au")
      val numberOfBulks = math.ceil(numElements.toFloat / AudioApiProperties.IndexBulkSize).toInt

      DB readOnly { implicit session =>
        for(i <- 0 until numberOfBulks) {
          func(
            sql"""select ${au.result.*} from ${AudioMetaInformation.as(au)} limit ${AudioApiProperties.IndexBulkSize} offset ${i * AudioApiProperties.IndexBulkSize}""".map(AudioMetaInformation(au)).list.apply()
          )
        }
      }
    }

    def audiosWithIdBetween(min: Long, max: Long): List[AudioMetaInformation] = {
      audioMetaInformationsWhere(sqls"au.id between $min and $max")
    }

    private def audioMetaInformationWhere(whereClause: SQLSyntax)(implicit session: DBSession = ReadOnlyAutoSession): Option[AudioMetaInformation] = {
      val au = AudioMetaInformation.syntax("au")
      sql"select ${au.result.*} from ${AudioMetaInformation.as(au)} where $whereClause".map(AudioMetaInformation(au)).single().apply()
    }

    private def audioMetaInformationsWhere(whereClause: SQLSyntax)(implicit session: DBSession = ReadOnlyAutoSession): List[AudioMetaInformation] = {
      val au = AudioMetaInformation.syntax("au")
      sql"select ${au.result.*} from ${AudioMetaInformation.as(au)} where $whereClause".map(AudioMetaInformation(au)).list().apply()
    }

  }
}
