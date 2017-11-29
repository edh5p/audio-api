/*
 * Part of NDLA audio_api.
 * Copyright (C) 2016 NDLA
 *
 * See LICENSE
 *
 */

package no.ndla.audioapi.service

import java.net.URL

import com.amazonaws.AmazonClientException
import com.amazonaws.services.s3.model.ObjectMetadata
import no.ndla.audioapi.integration.MigrationAudioMeta
import no.ndla.audioapi.model.api.ImportException
import no.ndla.audioapi.model.domain.AudioMetaInformation
import no.ndla.audioapi.{TestEnvironment, UnitSuite}
import no.ndla.network.model.HttpRequestException
import org.mockito.Mockito._
import org.mockito.Matchers._

import scala.util.{Failure, Success}

class ImportServiceTest extends UnitSuite with TestEnvironment {
  val service = new ImportService

  val s3ObjectMock = mock[ObjectMetadata]
  val audioId = "1234"
  val defaultMigrationAudioMeta = MigrationAudioMeta("1", "1", "title", "file.mp3", "http://something.com", "audio/mpeg", "1024", None, "by-sa", Seq())

  override def beforeEach() = {
    when(s3ObjectMock.getContentLength).thenReturn(1024)
    when(s3ObjectMock.getContentType).thenReturn("audio/mp3")
  }

  test("importAudio returns Failure if call to migration api failes") {
    val audioId = "1234"
    val exceptionMock = mock[HttpRequestException]
    when(migrationApiClient.getAudioMetaData(audioId)).thenReturn(Failure(exceptionMock))
    service.importAudio(audioId) should equal (Failure(exceptionMock))
  }

  test("importAudio returns Failure if upload to S3 fails") {
    val clientException = mock[AmazonClientException]

    when(migrationApiClient.getAudioMetaData(audioId)).thenReturn(Success(Seq(defaultMigrationAudioMeta)))

    when(audioStorage.getObjectMetaData(any[String])).thenReturn(Failure(mock[AmazonClientException]))
    when(audioStorage.storeAudio(any[URL], any[String], any[String], any[String])).thenReturn(Failure(clientException))
    service.importAudio(audioId) should equal (Failure(clientException))
  }

  test("importAudio updates the database entry if already exists") {
    val audioPath = "audio/file.mp3"
    val existingAudioMeta = mock[AudioMetaInformation]

    when(migrationApiClient.getAudioMetaData(audioId)).thenReturn(Success(Seq(defaultMigrationAudioMeta)))
    when(audioStorage.getObjectMetaData(any[String])).thenReturn(Failure(mock[AmazonClientException]))
    when(audioStorage.storeAudio(any[URL], any[String], any[String], any[String])).thenReturn(Success(s3ObjectMock))
    when(tagsService.forAudio("1")).thenReturn(List())
    when(audioRepository.withExternalId(defaultMigrationAudioMeta.nid)).thenReturn(Some(existingAudioMeta))
    when(existingAudioMeta.id).thenReturn(Some(1: Long))
    when(audioRepository.update(any[AudioMetaInformation], any[Long])).thenReturn(Success(existingAudioMeta))

    service.importAudio(audioId) should equal (Success(existingAudioMeta))
    verify(audioRepository, times(1)).update(any[AudioMetaInformation], any[Long])
  }

  test("importAudio inserts a new database entry if already exists") {
    val audioPath = "audio/file.mp3"
    val newAudioMeta = mock[AudioMetaInformation]

    when(migrationApiClient.getAudioMetaData(audioId)).thenReturn(Success(Seq(defaultMigrationAudioMeta)))
    when(audioStorage.storeAudio(any[URL], any[String], any[String], any[String])).thenReturn(Success(s3ObjectMock))
    when(tagsService.forAudio("1")).thenReturn(List())
    when(audioRepository.withExternalId(defaultMigrationAudioMeta.nid)).thenReturn(None)
    when(audioRepository.insertFromImport(any[AudioMetaInformation], any[String])).thenReturn(Success(newAudioMeta))

    service.importAudio(audioId) should equal (Success(newAudioMeta))
    verify(audioRepository, times(1)).insertFromImport(any[AudioMetaInformation], any[String])
  }

  test("That mapOldToNewLicenseKey throws on invalid license") {
    assertThrows[ImportException] {
      service.mapOldToNewLicenseKey("publicdomain")
    }
  }

  test("That mapOldToNewLicenseKey converts correctly") {
    service.mapOldToNewLicenseKey("nolaw") should be("cc0")
    service.mapOldToNewLicenseKey("noc") should be("pd")
  }

  test("That mapOldToNewLicenseKey converts nolaw to cc0") {
    service.mapOldToNewLicenseKey("by-sa") should be("by-sa")
  }
}
