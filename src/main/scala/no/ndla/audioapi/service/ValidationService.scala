package no.ndla.audioapi.service

import no.ndla.audioapi.model.api.{ValidationException, ValidationMessage}
import no.ndla.audioapi.model.domain._
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist
import no.ndla.mapping.ISO639.get6391CodeFor6392CodeMappings
import no.ndla.mapping.License.getLicense
import org.scalatra.servlet.FileItem

import scala.util.{Failure, Success, Try}

trait ValidationService {
  val validationService: ValidationService

  class ValidationService {
    def validateAudioFile(audioFile: FileItem): Option[ValidationMessage] = {
      val validMimeType = "audio/mp3"
      val actualMimeType = audioFile.contentType.getOrElse("")

      if (actualMimeType != validMimeType) {
        return Some(ValidationMessage("file", s"The file ${audioFile.name} is not a valid audio file. Only valid type is '$validMimeType', but was '$actualMimeType'"))
      }

      audioFile.name.toLowerCase.endsWith(".mp3") match {
        case false => Some(ValidationMessage("file", s"The file ${audioFile.name} does not have a known file extension. Must be .mp3"))
        case true => None
      }
    }

    def validate(audio: AudioMetaInformation): Try[AudioMetaInformation] = {
      val validationMessages = audio.titles.flatMap(title => validateTitle("title", title))  ++
        validateCopyright(audio.copyright) ++
        validateTags(audio.tags)

      validationMessages match {
        case head :: tail => Failure(new ValidationException(errors=head :: tail))
        case _ => Success(audio)
      }
    }

    private def validateTitle(fieldPath: String, title: Title): Seq[ValidationMessage] = {
      containsNoHtml(fieldPath, title.title).toList ++
        validateLanguage(fieldPath, title.language)
    }

    def validateCopyright(copyright: Copyright): Seq[ValidationMessage] = {
      validateLicense(copyright.license).toList ++
      copyright.authors.flatMap(validateAuthor) ++
      copyright.origin.flatMap(origin => containsNoHtml("copyright.origin", origin))
    }

    def validateLicense(license: String): Seq[ValidationMessage] = {
      getLicense(license) match {
        case None => Seq(ValidationMessage("license.license", s"$license is not a valid license"))
        case _ => Seq()
      }
    }

    def validateAuthor(author: Author): Seq[ValidationMessage] = {
      containsNoHtml("author.type", author.`type`).toList ++
        containsNoHtml("author.name", author.name).toList
    }

    def validateTags(tags: Seq[Tag]): Seq[ValidationMessage] = {
      tags.flatMap(tagList => {
        tagList.tags.flatMap(containsNoHtml("tags.tags", _)).toList :::
          validateLanguage("tags.language", tagList.language).toList
      })
    }

    private def containsNoHtml(fieldPath: String, text: String): Option[ValidationMessage] = {
      Jsoup.isValid(text, Whitelist.none()) match {
        case true => None
        case false => Some(ValidationMessage(fieldPath, "The content contains illegal html-characters. No HTML is allowed"))
      }
    }

    private def validateLanguage(fieldPath: String, languageCode: Option[String]): Option[ValidationMessage] = {
      languageCode.flatMap(lang =>
        languageCodeSupported6391(lang) match {
          case true => None
          case false => Some(ValidationMessage(fieldPath, s"Language '$languageCode' is not a supported value."))
        })
    }

    private def languageCodeSupported6391(languageCode: String): Boolean =
      get6391CodeFor6392CodeMappings.exists(_._2 == languageCode)

  }
}
