package ch.epfl.scala.index
package data
package project

import cleanup._
import model._
import model.misc._
import model.release._
import maven.{ReleaseModel, SbtPluginTarget}
import bintray._
import github._

import ch.epfl.scala.index.data.LocalRepository.BintraySbtPlugins
import ch.epfl.scala.index.data.project.ProjectConvert.ProjectSeed

import elastic.SaveLiveData
import com.github.nscala_time.time.Imports._
import org.joda.time.DateTime

import org.slf4j.LoggerFactory

class ProjectConvert(paths: DataPaths, githubDownload: GithubDownload)
    extends BintrayProtocol {

  private val log = LoggerFactory.getLogger(getClass)

  private val artifactMetaExtractor = new ArtifactMetaExtractor(paths)

  /**
   * @param pomsRepoSha poms and associated meta information reference
   * @param stored read user update projects from disk
   * @param cachedReleases use previous released cached to workarround elasticsearch consistency (write, read)
   */
  def apply(
      pomsRepoSha: List[(ReleaseModel, LocalRepository, String)],
      stored: Boolean = true,
      cachedReleases: Map[Project.Reference, Set[Release]] = Map()
  ): List[(Project, Set[Release])] = {

    val githubRepoExtractor = new GithubRepoExtractor(paths)

    log.info("Collecting Metadata")

    val pomsAndMetaClean = PomMeta(pomsRepoSha, paths).flatMap {
      case PomMeta(pom, created, resolver) =>
        for {
          artifactMeta <- artifactMetaExtractor(pom)
          version <- SemanticVersion(pom.version)
          github <- githubRepoExtractor(pom)
        } yield
          (github,
           artifactMeta.artifactName,
           artifactMeta.scalaTarget,
           pom,
           created,
           resolver,
           version,
           artifactMeta.isNonStandard)
    }

    log.info("Convert POMs to Project")
    val licenseCleanup = new LicenseCleanup(paths)

    def pomToMavenReference(pom: maven.ReleaseModel) =
      MavenReference(pom.groupId, pom.artifactId, pom.version)

    def maxMinRelease(
        releases: Set[Release]
    ): (Option[String], Option[String]) = {
      def sortDate(rawDates: List[String]): List[String] = {
        rawDates
          .map(PomMeta.format.parseDateTime)
          .sorted(Descending[DateTime])
          .map(PomMeta.format.print)
      }

      val dates = for {
        release <- releases.toList
        date <- release.released.toList
      } yield date

      val sorted = sortDate(dates)
      (sorted.headOption, sorted.lastOption)
    }

    val storedProjects = SaveLiveData.storedProjects(paths)

    val projectsAndReleases = pomsAndMetaClean
      .groupBy {
        case (githubRepo, _, _, _, _, _, _, _) => githubRepo
      }
      .map {
        case (githubRepo @ GithubRepo(organization, repository), vs) =>
          val projectReference = Project.Reference(organization, repository)

          val cachedReleasesForProject =
            cachedReleases.get(projectReference).getOrElse(Set())

          val releases = vs.map {
            case (_,
                  artifactName,
                  target,
                  pom,
                  created,
                  resolver,
                  version,
                  isNonStandardLib) =>
              val (targetType,
                   fullScalaVersion,
                   scalaVersion,
                   scalaJsVersion,
                   scalaNativeVersion,
                   sbtVersion) =
                ScalaTarget.split(target)

              Release(
                maven = pomToMavenReference(pom),
                reference = Release.Reference(
                  organization,
                  repository,
                  artifactName,
                  version,
                  target
                ),
                resolver = resolver,
                name = pom.name,
                description = pom.description,
                released = created,
                licenses = licenseCleanup(pom),
                isNonStandardLib = isNonStandardLib,
                id = None,
                liveData = false,
                scalaDependencies = Seq(),
                javaDependencies = Seq(),
                reverseDependencies = Seq(),
                internalDependencies = Seq(),
                targetType = targetType.getOrElse(Java).toString,
                // for artifact publish with binary, full will return binary
                // ex cats_2.11 => 2.11
                fullScalaVersion = fullScalaVersion,
                scalaVersion = scalaVersion,
                scalaJsVersion = scalaJsVersion,
                scalaNativeVersion = scalaNativeVersion,
                sbtVersion = sbtVersion
              )
          }.toSet ++ cachedReleasesForProject

          val releaseCount = releases.map(_.reference.version).toSet.size

          val (max, min) = maxMinRelease(releases)

          val defaultStableVersion =
            if (stored)
              storedProjects
                .get(Project.Reference(organization, repository))
                .map(_.defaultStableVersion)
                .getOrElse(true)
            else true

          val releaseOptions = DefaultRelease(
            repository,
            ReleaseSelection.empty,
            releases,
            None,
            defaultStableVersion
          )

          val github = GithubReader(paths, githubRepo)
          val seed =
            ProjectSeed(
              organization = organization,
              repository = repository,
              github = github,
              artifacts = releaseOptions.map(_.artifacts.sorted).getOrElse(Nil),
              releaseCount = releaseCount,
              defaultArtifact = releaseOptions.map(_.release.reference.artifact),
              created = min,
              updated = max
            )

          (seed, releases)
      }
      .toList

    log.info("Dependencies & Reverse Dependencies")

    val mavenReferenceToReleaseReference
      : Map[MavenReference, Release.Reference] =
      projectsAndReleases
        .flatMap {
          case (_, releases) => releases
        }
        .map(release => (release.maven, release.reference))
        .toMap

    def dependencyToMaven(dependency: maven.Dependency) =
      MavenReference(dependency.groupId,
                     dependency.artifactId,
                     dependency.version)

    val poms = pomsAndMetaClean.map { case (_, _, _, pom, _, _, _, _) => pom }

    def link(reverse: Boolean) = {
      poms.foldLeft(Map[Release.Reference, Seq[Dependency]]()) {
        case (cache, pom) =>
          pom.dependencies.foldLeft(cache) {
            case (cache0, dependency) =>
              val depMavenRef = dependencyToMaven(dependency)
              val pomMavenRef = pomToMavenReference(pom)
              val depRef = mavenReferenceToReleaseReference.get(depMavenRef)
              val pomRef = mavenReferenceToReleaseReference.get(pomMavenRef)

              (depRef, pomRef) match {

                // Scala Library is a reverse dependency of almost all projects anyway
                case (Some(dependencyReference), _)
                    if (reverse && dependencyReference.isScalaLib) =>
                  cache0

                /* We have both, scala -> scala reference */
                case (Some(dependencyReference), Some(pomReference)) =>
                  val (source, target) =
                    if (reverse) (dependencyReference, pomReference)
                    else (pomReference, dependencyReference)
                  upsert(cache0,
                         source,
                         ScalaDependency(target, dependency.scope))

                /* dependency is scala reference - works now only if reverse */
                case (Some(dependencyReference), None) =>
                  if (reverse)
                    upsert(cache0,
                           dependencyReference,
                           JavaDependency(pomMavenRef, dependency.scope))
                  else cache0

                /* works only if not reverse */
                case (None, Some(pomReference)) =>
                  if (!reverse)
                    upsert(cache0,
                           pomReference,
                           JavaDependency(depMavenRef, dependency.scope))
                  else cache0

                /* java -> java: should not happen actually */
                case (None, None) =>
                  log.error(
                    s"no reference discovered for $pomMavenRef -> $depMavenRef"
                  )
                  cache0
              }
          }
      }
    }

    val dependenciesCache = link(reverse = false)
    val reverseDependenciesCache = link(reverse = true)

    def findDependencies(release: Release): Seq[Dependency] = {
      dependenciesCache.getOrElse(release.reference, Seq())
    }

    def findReverseDependencies(release: Release): Seq[Dependency] = {
      reverseDependenciesCache.getOrElse(release.reference, Seq())
    }

    def collectDependencies(
        releases: Set[Release],
        f: Release.Reference => Option[String]
    ): Set[String] = {
      (for {
        release <- releases.toList
        dependency <- release.scalaDependencies.toList
        r <- f(dependency.reference).toList
      } yield r).toSet
    }

    def dependencies(releases: Set[Release]): Set[String] =
      collectDependencies(releases, r => Some(r.name))

    def belongsTo(reference: Project.Reference): Dependency => Boolean = {
      //A scala project can only have scala internal dependencies
      case scalaDependency: ScalaDependency =>
        reference.organization == scalaDependency.reference.organization &&
          reference.repository == scalaDependency.reference.repository
      case _ => false
    }

    projectsAndReleases.map {
      case (seed, releases) =>
        val releasesWithDependencies = releases.map { release =>
          val dependencies = findDependencies(release)
          val externalDependencies =
            dependencies.filterNot(belongsTo(seed.reference))
          val internalDependencies =
            dependencies.filter(belongsTo(seed.reference))
          release.copy(
            scalaDependencies = externalDependencies.collect {
              case sd: ScalaDependency => sd
            },
            javaDependencies = externalDependencies.collect {
              case jd: JavaDependency => jd
            },
            reverseDependencies = findReverseDependencies(release).collect {
              case sd: ScalaDependency => sd
            },
            internalDependencies = internalDependencies.collect {
              case sd: ScalaDependency => sd
            }
          )
        }

        val project =
          seed.toProject(
            targetType = releases.map(_.targetType).toSet,
            fullScalaVersion = releases.flatMap(_.fullScalaVersion).toSet,
            scalaVersion = releases.flatMap(_.scalaVersion).toSet,
            scalaJsVersion = releases.flatMap(_.scalaJsVersion).toSet,
            scalaNativeVersion = releases.flatMap(_.scalaNativeVersion).toSet,
            sbtVersion = releases.flatMap(_.sbtVersion).toSet,
            dependencies = dependencies(releasesWithDependencies),
            dependentCount = releasesWithDependencies.view
              .flatMap(_.reverseDependencies.map(_.reference))
              .filterNot(
                release =>
                  belongsTo(seed.reference)(ScalaDependency(release, None))
              )
              .map(_.projectReference)
              .size // Note: we don’t need to call `.distinct` because `releases` is a `Set`
          )

        val updatedProject =
          if (stored) {
            storedProjects
              .get(project.reference)
              .map(_.update(project, paths, githubDownload, fromStored = true))
              .getOrElse(project)
          } else project

        (updatedProject, releasesWithDependencies)
    }
  }
}

object ProjectConvert {

  /** Intermediate data structure */
  case class ProjectSeed(
      organization: String,
      repository: String,
      github: Option[GithubInfo],
      artifacts: List[String],
      defaultArtifact: Option[String],
      releaseCount: Int,
      created: Option[String],
      updated: Option[String]
  ) {

    def reference = Project.Reference(organization, repository)

    def toProject(targetType: Set[String],
                  fullScalaVersion: Set[String],
                  scalaVersion: Set[String],
                  scalaJsVersion: Set[String],
                  scalaNativeVersion: Set[String],
                  sbtVersion: Set[String],
                  dependencies: Set[String],
                  dependentCount: Int): Project =
      Project(
        organization = organization,
        repository = repository,
        github = github,
        artifacts = artifacts,
        defaultArtifact = defaultArtifact,
        releaseCount = releaseCount,
        created = created,
        updated = updated,
        targetType = targetType,
        fullScalaVersion = fullScalaVersion,
        scalaVersion = scalaVersion,
        scalaJsVersion = scalaJsVersion,
        scalaNativeVersion = scalaNativeVersion,
        sbtVersion = sbtVersion,
        dependencies = dependencies,
        dependentCount = dependentCount
      )
  }

}
