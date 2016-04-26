package api

import akka.actor.{ActorRef, ActorSystem, Props}
import core.{CoordinatorActor, ExperimentActor, JobResults, RestMessage}
import dao.{JobResultsDAL, LdsmDAL}
import spray.http._
import spray.routing.Route
import spray.json._
import MediaTypes._


// this trait defines our service behavior independently from the service actor
class JobService(val chronosService: ActorRef,
                 val resultDatabase: JobResultsDAL,
                 val federationDatabase: Option[JobResultsDAL],
                 val ldsmDatabase: LdsmDAL)(implicit system: ActorSystem) extends JobServiceDoc with PerRequestCreator with DefaultJsonFormats {

  override def context = system
  val routes: Route = initJob ~ mining ~ experiment ~ listMethods

  import JobDto._
  import CoordinatorActor._
  import ApiJsonSupport._

  implicit object EitherErrorSelector extends ErrorSelector[ErrorResponse.type] {
    def apply(v: ErrorResponse.type): StatusCode = StatusCodes.BadRequest
  }

  override def initJob: Route = path("job") {
    post {
      entity(as[JobDto]) { job =>
        chronosJob() {
          Start(job)
        }
      }
    }
  }

  override def listMethods: Route = path("list-methods") {

    val mock =
      """
    {
      "algorithms": {

        "linearRegression": {
        "type": "regression",
        "docker_image": "hbpmip/r-linear-regression:52198fd",
        "environment": "R",
        "description": "Linear Regression... ",
        "results": {},
        "covariables": {
        "number": "1..n",
        "domains": "double",
        "mixed": false
      },
        "grouping": {
        "number": "0..n",
        "domains": ["binominal", "multinominal"],
        "mixed": true
      }
      },
        "anova": {
        "type": "regression",
        "docker_image": "hbpmip/r-linear-regression:52198fd",
        "environment": "R",
        "description": "ANOVA... ",
        "results": {},
        "covariables": {
        "number": "0..n",
        "domains": "double",
        "mixed": false
      },
        "grouping": {
        "number": "1..n",
        "domains": ["binominal", "multinominal"],
        "mixed": true
      }
      },
        "knn": {
        "type": ["regression", "classification"],
        "docker_image": "hbpmip/java-rapidminer:latest",
        "environment": "RapidMiner",
        "description": "Knn... ",
        "results": {},
        "covariables": {
        "number": "1..n",
        "domains": "double",
        "mixed": false
      },
        "grouping": {
        "number": "0"
      }
      },
        "naiveBayes": {
        "type": "classification",
        "docker_image": "hbpmip/java-rapidminer:latest",
        "environment": "RapidMiner",
        "description": "Naive Bayes... ",
        "results": {},
        "covariables": {
        "number": "1..n",
        "domains": "double",
        "mixed": false
      },
        "grouping": {
        "number": "0"
      }
      },
        "tSNE": {
        "type": "features_extraction",
        "docker_image": "hbpmip/r-tsne:latest",
        "environment": "R",
        "covariables": {
        "number": "1..n",
        "domains": "double",
        "mixed": false
      },
        "grouping": {
        "number": "0"
      }
      },
        "boxplot": {
        "type": "stats",
        "docker_image": "hbpmip/r-summary-stats:52198fd"

      },
        "summarystatistics": {
        "type": "stats",
        "docker_image": "hbpmip/r-summary-stats:52198fd"

      }
      },
      "validations": {
        "kFoldCrossValidation": {
        "parameters": [
      {"name" : "k", "type": "integer", "default": 5, "interval": "[1, 100]"}
        ]
      }
      }
    }
      """

    get {
      respondWithMediaType(`application/json`) {
        complete(mock.parseJson.compactPrint)
      }
    }
  }

  //TODO TO be changed that it can take parameters of alrogithms!!!!!
  override def mining: Route = path("mining") {
    import FunctionsInOut._

    post {
      entity(as[SimpleQuery]) {
        case SimpleQuery(variables, covariables, groups, _, Algorithm(c, l, p)) if c == "" || c == "data" => {
          ctx => ctx.complete(ldsmDatabase.queryData({ variables ++ covariables ++ groups }.distinct.map(_.code)))
        }
        case query: SimpleQuery => {
          val job = query2job(query)
          chronosJob(RequestProtocol) {
            Start(job)
          }
        }
      }
    }
  }

  override def experiment: Route = path("experiment") {
    import FunctionsInOut._

    post {
      entity(as[ExperimentQuery]) {
        case query: ExperimentQuery => {
          val job = query2job(query)
          ExperimentJob(RequestProtocol) {
            ExperimentActor.Start(job)
          }
        }
      }
    }
  }

  def chronosJob(jobResultsFactory: JobResults.Factory = JobResults.defaultFactory)(message : RestMessage): Route =
    ctx => perRequest(ctx, CoordinatorActor.props(chronosService, resultDatabase, federationDatabase, jobResultsFactory), message)

  def ExperimentJob(jobResultsFactory: JobResults.Factory = JobResults.defaultFactory)(message : RestMessage): Route =
    ctx => perRequest(ctx, context.actorOf(Props(classOf[ExperimentActor], chronosService, resultDatabase, federationDatabase, jobResultsFactory)), message)

}
