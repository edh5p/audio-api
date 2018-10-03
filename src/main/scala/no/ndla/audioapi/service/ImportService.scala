/*
 * Part of NDLA audio_api.
 * Copyright (C) 2016 NDLA
 *
 * See LICENSE
 *
 */

package no.ndla.audioapi.service

import java.net.URL

import com.typesafe.scalalogging.LazyLogging
import no.ndla.audioapi.auth.User
import no.ndla.audioapi.integration.{MigrationApiClient, MigrationAudioMeta, MigrationAuthor}
import no.ndla.audioapi.model.api.ImportException
import no.ndla.audioapi.model.domain._
import no.ndla.audioapi.model.{Language, domain}
import no.ndla.audioapi.repository.AudioRepository
import no.ndla.audioapi.AudioApiProperties._
import no.ndla.mapping.License._

import scala.util.Try
import com.netaporter.uri.dsl._
import no.ndla.mapping.LicenseDefinition

trait ImportService {
  this: MigrationApiClient with AudioStorageService with AudioRepository with TagsService with User with Clock =>
  val importService: ImportService

  class ImportService extends LazyLogging {

    def importAudio(audioId: String): Try[domain.AudioMetaInformation] = {
      migrationApiClient.getAudioMetaData(audioId).flatMap(uploadAndPersist)
    }

    private def uploadAndPersist(audioMeta: Seq[MigrationAudioMeta]): Try[domain.AudioMetaInformation] = {
      val audioFilePaths = audioMeta.map(uploadAudioFile(_).get)
      persistMetaData(audioMeta, audioFilePaths)
    }

    private def cleanAudioMeta(audio: domain.AudioMetaInformation): domain.AudioMetaInformation = {
      val titleLanguages = audio.titles.map(_.language)
      val tags = audio.tags.filter(tag => titleLanguages.contains(tag.language))

      audio.copy(tags = tags)
    }

    private def toNewAuthorType(author: MigrationAuthor): domain.Author = {
      val creatorMap = (oldCreatorTypes zip creatorTypes).toMap.withDefaultValue(None)
      val processorMap = (oldProcessorTypes zip processorTypes).toMap.withDefaultValue(None)
      val rightsholderMap = (oldRightsholderTypes zip rightsholderTypes).toMap.withDefaultValue(None)

      (creatorMap(author.`type`.toLowerCase),
       processorMap(author.`type`.toLowerCase),
       rightsholderMap(author.`type`.toLowerCase)) match {
        case (t: String, _, _) => domain.Author(t.capitalize, author.name)
        case (_, t: String, _) => domain.Author(t.capitalize, author.name)
        case (_, _, t: String) => domain.Author(t.capitalize, author.name)
        case (_, _, _)         => domain.Author(author.`type`, author.name)
      }
    }

    private[service] def oldToNewLicenseKey(license: String): Option[LicenseDefinition] = {
      val licenses = Map(
        "by" -> "CC-BY-4.0",
        "by-sa" -> "CC-BY-SA-4.0",
        "by-nc" -> "CC-BY-NC-4.0",
        "by-nd" -> "CC-BY-ND-4.0",
        "by-nc-sa" -> "CC-BY-NC-SA-4.0",
        "by-nc-nd" -> "CC-BY-NC-ND-4.0",
        "by-3.0" -> "CC-BY-4.0",
        "by-sa-3.0" -> "CC-BY-SA-4.0",
        "by-nc-3.0" -> "CC-BY-NC-4.0",
        "by-nd-3.0" -> "CC-BY-ND-4.0",
        "by-nc-sa-3.0" -> "CC-BY-NC-SA-4.0",
        "by-nc-nd-3.0" -> "CC-BY-NC-ND-4.0",
        "copyrighted" -> "COPYRIGHTED",
        "cc0" -> "CC0-1.0",
        "pd" -> "PD",
        "nolaw" -> "CC0-1.0",
        "noc" -> "PD"
      )
      val newLicense = getLicense(licenses.getOrElse(license, license))
      if (newLicense.isEmpty) {
        throw new ImportException(s"License $license is not supported.")
      }
      newLicense
    }

    private[service] def toDomainCopyright(license: String, authors: Seq[MigrationAuthor]): domain.Copyright = {
      val origin = authors.find(_.`type`.toLowerCase() == "opphavsmann")

      val creators = authors.filter(a => oldCreatorTypes.contains(a.`type`.toLowerCase)).map(toNewAuthorType)
      val processors = authors.filter(a => oldProcessorTypes.contains(a.`type`.toLowerCase)).map(toNewAuthorType)
      val rightsholders = authors.filter(a => oldRightsholderTypes.contains(a.`type`.toLowerCase)).map(toNewAuthorType)
      val domainLicense = oldToNewLicenseKey(license).map(_.license.toString).getOrElse("COPYRIGHTED")

      domain.Copyright(
        domainLicense,
        origin.map(_.name),
        creators,
        processors,
        rightsholders,
        None,
        None,
        None
      )

    }

    private[service] def persistMetaData(audioMeta: Seq[MigrationAudioMeta],
                                         audioObjects: Seq[Audio]): Try[domain.AudioMetaInformation] = {
      val mainNode = audioMeta.find(_.isMainNode).get
      val nodeData = migrationApiClient.getNodeData(mainNode.nid)

      val audioTitles = audioMeta.map(x => Title(x.title, Language.languageOrUnknown(x.language)))

      val nodeDataTitles = nodeData
        .map(_.titles.map(t => { Title(t.title, Language.languageOrUnknown(emptySomeToNone(Some(t.language)))) }))
        .getOrElse(Seq.empty)

      // Combine titles from audio meta and node data with only one from each language
      val titles = (audioTitles ++ nodeDataTitles)
        .foldLeft(List.empty[Title]) { (acc, elem) =>
          acc.find(i => i.language == elem.language) match {
            case Some(_)                  => acc
            case None if elem.title != "" => elem :: acc
            case None                     => acc
          }
        }
        .reverse

      val authors = audioMeta.flatMap(_.authors).distinct

      val copyright = toDomainCopyright(mainNode.license, authors)
      val domainMetaData = cleanAudioMeta(
        domain.AudioMetaInformation(None,
                                    None,
                                    titles,
                                    audioObjects,
                                    copyright,
                                    tagsService.forAudio(mainNode.nid),
                                    authUser.userOrClientid(),
                                    clock.now()))

      audioRepository.withExternalId(mainNode.nid) match {
        case None => audioRepository.insertFromImport(domainMetaData, mainNode.nid)
        case Some(existingAudio) =>
          audioRepository.update(domainMetaData.copy(revision = existingAudio.revision), existingAudio.id.get)
      }
    }

    private def uploadAudioFile(audioMeta: MigrationAudioMeta): Try[Audio] = {
      audioStorage
        .getObjectMetaData(audioMeta.fileName)
        .orElse(
          audioStorage.storeAudio(new URL(audioMeta.url.withScheme("https")),
                                  audioMeta.mimeType,
                                  audioMeta.fileSize,
                                  audioMeta.fileName))
        .map(
          s3ObjectMeta =>
            Audio(audioMeta.fileName,
                  s3ObjectMeta.getContentType,
                  s3ObjectMeta.getContentLength,
                  Language.languageOrUnknown(audioMeta.language)))
    }

  }
}
