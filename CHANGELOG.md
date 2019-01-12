
# Changelog


## 2.9.0 - 2019-01-12

* Update algorithms
   * hbpmip/python-anova:0.4.5
   * hbpmip/python-correlation-heatmap:0.5.0
   * hbpmip/python-histograms:0.6.2
   * hbpmip/python-summary-statistics:0.4.1
   * hbpmip/python-sgd-linear-model:0.3.4
   * hbpmip/python-sgd-naive-bayes:0.3.4
   * hbpmip/python-sgd-neural-network:0.3.4
   * hbpmip/python-gradient-boosting:0.3.4
   * hbpmip/python-correlation-heatmap:0.5.1
   * hbpmip/python-distributed-pca:0.5.1
   * hbpmip/python-distributed-kmeans:0.2.2
   * hbpmip/python-tsne:0.4.3
   * hbpmip/r-ggparci:0.2.2
   * hbpmip/python-jsi-hinmine:0.3.1
   * hbpmip/python-jsi-hedwig:1.0.9
* Update Woken-validation to 2.5.9
* API: /cluster - expose Akka management checks
* SPI: Describe tables in the configutation
* Do not always require covariables to exist for distributed queries
* Stream support for websocket client
* Better descriptions of algorithms
* Improve shutdown of Akka services in the cluster
* Add validation checks for feature tables
* Validation of experiment queries
* Quote all column and table names in generated SQL, table and column names are now case-sensitive and can be non standard identifiers
* Monitoring: add Akka management
* Monitoring: use sup library for functional-oriented definition of health checks.
* Monitoring: Add health checks for cluster status, database status, execution of Docker algorithms with Chronos/Mesos, delegation of computations to Woken validation workers
* Self checks for Akka server and web server
* [dev] Remove cyclic dependencies in code and add tests against cyclic dependencies
* [dev] Refactoring database access to features
* [dev] Heavy refactoring of internal services, wrap them into Resource to ensure orderly startup and cleanup of the services
* [dev] Reorganisation of packages
* [dev] Reify several concepts: TableId, FeaturesTableDescription, backends for WokenWorker and AlgorithmExecutor
* [dev] Use gen_features_table_seq defined in Woken db
* [dev] Update many library dependencies, including Akka 2.5.19, Cats 1.5.0
* [dev] Update woken-messages to 2.8.4
* [test] Test mining queries
* [test] Update and fix integration tests
* [test] Add unit tests for FeaturesTableRepositoryDAO and ExtendedFeaturesTableRepositoryDAO
* [fix] Fix validation of tables at startup
* [fix] Fix startup of services
* [fix] Refactor and fix cross-validation: use a temporary table in Postgres to create the random k-folds
* [fix] Fix filtering queries

## 2.8.0 - 2018-05-23

* New algorithms
   * hbpmip/python-distributed-kmeans:0.2.1 distributed
* Update algorithms
   * hbpmip/python-anova:0.4.4
   * hbpmip/python-linear-regression:0.3.1 distributed
   * hbpmip/python-summary-statistics:0.4.0 distributed
   * hbpmip/python-histograms:0.6.1
   * hbpmip/python-knn:0.4.0
   * hbpmip/python-distributed-kmeans:0.2.0
   * hbpmip/python-sgd-linear-model:0.3.3
   * hbpmip/python-sgd-naive-bayes:0.3.3
   * hbpmip/python-sgd-neural-network:0.3.3
   * hbpmip/python-gradient-boosting:0.3.3
   * hbpmip/python-distributed-pca:0.4.0 distributed
   * hbpmip/python-correlation-heatmap:0.4.0
* Update woken-validation to 2.5.3
* Enable distributed support for histograms, statistics summary, kmeans
* HinMine and Heatmaply algorithms considered now ready for production
* Distributed algorithms: support map-reduce
* Provenance: keep track of intial query in the response
* Monitoring: trace actions in actors
* Select only covariables and groupings known locally
* Exchange large messages between Woken nodes using streaming messages over websockets
* [dev] Introduce distributedExecutionPlan
* [dev] Update woken-messages to 2.8.1
* [test] Integration tests for HinMine and Heatmaply
* [test] Integration tests for distributed algorithms in a federation
* [fix] Report errors on websockets, queries
* [fix] Improve execution of remote validations in an experiment
* [fix] Fix more http connection leaks on Chronos client
* [fix] Hedwig should work for nominal and numerical covariables
* [fix] improve shuffling during K-fold cross validation
* [fix] Fix query for cross validation

2.7.0 - 2018-05-15

* New algorithms
   * hbpmip/python-distributed-pca:0.3.1
* Update algorithms
   * hbpmip/python-histograms:0.5.1
   * hbpmip/python-summary-statistics:0.3.4
   * hbpmip/python-anova:0.4.3
   * hbpmip/python-linear-regression:0.2.2
   * hbpmip/python-sgd-linear-model:0.3.0
   * hbpmip/python-sgd-naive-bayes:0.3.0
   * hbpmip/python-sgd-neural-network:0.3.0
   * hbpmip/python-gradient-boosting:0.3.0
   * hbpmip/python-knn:0.3.1
   * hbpmip/python-correlation-heatmap:0.3.1
   * hbpmip/python-tsne:0.4.2
   * hbpmip/python-jsi-hedwig:1.0.7
* Update woken-validation to 2.5.0
* Handle remote validation requests in an experiment
* Stabilisation: Use DistPubSub to communicate with Woken validation
* Update Linear regression algo to support logistic regression on nominal variables
* [dev] Update Woken-messages to 2.7.5
* [fix] Fix type in algo spec
* [fix] Improve labels and parameters for algorithms
* [fix] Remove HinMine as it crashes Woken
* [fix] Change type for algo ggparci to features_extraction
* [fix] Naive Bayes: configure only for classification

## 2.6.0 - 2018-05-01

* Update algorithms
   * hbpmip/python-anova:0.4.2
   * hbpmip/python-histograms:0.5.0
   * hbpmip/python-knn:0.3.0
   * hbpmip/python-linear-regression:0.2.0
   * hbpmip/python-summary-statistics:0.3.2
   * hbpmip/python-correlation-heatmap:0.1.5
   * hbpmip/python-sgd-linear-model:0.2.0
   * hbpmip/python-sgd-naive-bayes:0.2.0
   * hbpmip/python-sgd-neural-network:0.2.0
   * hbpmip/python-gradient-boosting:0.2.0
   * hbpmip/python-tsne:0.4.1
   * hbpmip/python-jsi-hinmine:0.2.3
   * hbpmip/python-jsi-hedwig:1.0.5
   * hbpmip/r-ggparci:0.2.1
* Update woken-validation to 2.4.11
* SPI: easy switch between Netty and Artery remoting
* SPI: Remove deprecated PARAM_MODEL_x env vars
* SPI: Configuration for CPU and memory allocated to a job
* SPI: Rename config for defaultJobMemory and Cpus
* Monitoring: Add dead letters monitor
* Use Artery TCP for Akka remoting
* Improve constraints for linear regression algorithm
* Define parameters for new algorithms
* Align config with Woken validation
* Improve reporting of invalid queries
* Akka: configure Coordinated shutdown
* [dev] Replace ActorLogging by LazyLogging
* [dev] Register the mainRouter as a distributed pubsub destination actor
* [dev] Add Metadata query actor, pool it to limit concurrent use
* [dev] Define algorithm engine to be able to switch engines
* [dev] Update woken-messages to 2.7.4
* [test] test many more algorithms
* [test] Add unit test for metadata queries actor
* [fix] Fix http connection leak on Chronos client
* [fix] Stabilise Chronos
* [fix] Increase size of payloads for messages exchanged between Woken and the portal or validation workers
* [fix] Metadata queries: support exhaustive argument

## 2.5.0 - 2018-04-19

* New algorithms
   * hbpmip/python-knn:0.2.3
   * hbpmip/python-sgd-linear-model:0.1.4
   * hbpmip/python-sgd-naive-bayes:0.1.4
   * hbpmip/python-sgd-neural-network:0.1.4
   * hbpmip/python-correlation-heatmap:0.1.2
   * hbpmip/python-gradient-boosting:0.1.4
   * hbpmip/python-jsi-hinmine:0.2.2
   * hbpmip/python-jsi-hedwig:1.0.4
* Update algorithms
   * hbpmip/python-histograms:0.4.3
   * hbpmip/python-linear-regression:0.1.1
   * hbpmip/python-summary-statistics:0.3.0
   * hbpmip/python-tsne:0.4.0
* Update woken-validation to 2.4.6
* API: /metadata/variables - add list of datasets where the variable is present into variables metadata
* SPI: allow overriding algorthims from environment variables, using for example PYTHON_HISTOGRAMS_IMAGE or PYTHON_LINEAR_REGRESSION_IMAGE
* SPI: define for each algorithm whether it supports null values
* SPI: Datasets config: always indicate list of tables
* SPI: Configure db pool size
* Check that the datasets exist in the features database on startup
* Support non predictive algorithms in experiments
* Improve self checks
* [dev] Update woken-messages to 2.7.1
* [dev] Update Akka to 2.5.12, Cats to 1.1.0
* [dev] Add Mining actor and Experiment query actor, pool them to limit concurrent use
* [dev] Move queries actors to dispatch package
* [test] Fix integration tests
* [test] Integration test for summary statistics
* [test] Update integration tests, experiment with KNN
* [fix] /metadata/datasets returns NPE
* [fix] Fix count features
* [fix] Handle tables without a 'dataset' column

## 2.4.0 - 2018-03-16

* Update algorithms
   * hbpmip/java-rapidminer-knn:0.2.2
   * hbpmip/java-rapidminer-naivebayes:0.2.1
   * hbpmip/python-histograms:0.3.6
   * hbpmip/python-linear-regression:0.0.7
   * hbpmip/python-summary-statistics:0.1.4
   * hbpmip/python-tsne:0.3.3
   * hbpmip/r-ggparci:0.2.0
* Update Woken validation to 2.3.1
* Queries are automatically filtered by datasets
* API: /metadata/datasets - list available datasets. Uses configuration as the source of information
* API: /metadata/variables - list variables and their metadata
* API: add /health and /readiness checks
* SPI: support mime types for Vega and Vega-lite graphs as results from algorithms
* SPI: support mime types for serialised results (using Python pickle, Java serialization, R RDS) from algorithms
* SPI: mime type for tabular data resource is now application/vnd.dataresource+json
* SPI: provide database configuration settings for Python-based algorithms using SQLAlchemy to connect to the database
* Monitoring: trace Akka HTTP requests (temporarily disabled), Akka remote requests
* Monitoring: host and JVM metrics
* [dev] Revert to Scala 2.11.12 for serialization compatibility with Woken-validation which requires Scala 2.11
* [dev] Improve initialisation order of services during startup
* [dev] Migrate conversion of FilterRules to sql where to woken-messages
* [dev] Common configuration files for Akka and Kamon
* [dev] Enable strace for debugging
* [dev] Update Akka http to 10.1.0-RC2, woken-messages to 2.6.1
* [dev] Add FeaturesRepository DAO to access features data in the db
* [test] New cases for experiment flows
* [test] Improve tests for validation flows
* [test] Update tests over websockets
* [fix] Stabilisation of mining and experiment queries
* [doc] Usage for this application

## 2.3.0 - 2018-02-20

* Update Woken validation to 2.1.6
* Update algorithms
   * python-anova:0.3.6
   * python-linear-regression:0.0.7
   * python-summary-statistics:0.1.0
   * python-tsne:0.3.3
* Add Akka-based dispatcher and HTTPS to connect to remote Woken instances
* Let's Encrypt support
* Queries can specify target table
* Add label to datasets
* Add Metadata for groups
* Add Kamon monitoring, with support for Zipkin and Prometheus
* [test] integration tests for distributed functionality
* [test] use synthetic datasets for testing
* [dev] Update to Sbt 1.1.0, Scala 2.12.4, woken-messages 2.4.8
* [dev] Various attempts at finding right serialization and Akka networking configuration
* [dev] Refactor VariablesMeta, Shapes and document JobResults
* [dev] Rebrand top level package to ch.chuv.lren.woken

## 2.2.0 - 2018-01-16

* Distributed datamining
* Distributed experiments
* Add support for Websockets to connect to remote Woken instances
* [dev] Rely on Akka streams for all distributed functionality, also for experiments
* [dev] Update to Akka 2.5.9

## 2.1.1 - 2017-12-14

* Clarify and update configuration

## 2.1.0 - 2017-12-11

* Requires Chronos 3.0.2 and Zookeeper 3.4.8
* Deep code refactoring, backend by more unit tests
* Monitor jobs on Chronos, track failures and report errors on those jobs
* [dev] Configuration uses Cats Validation
* [dev] Favor immutable data structures

## 2.0.2 - 2017-10-23

* Update Akka to 2.3.16

## 2.0.1 - 2017-10-13

* Move cross-validation module to its own project (woken-validation)
* Extract woken-messages library and make it a dependency
* Add a MasterRouter actor, use Akka to communicate with Portal backend
* Pass variables metatada to algorithms as PARAM_meta variable

## 2.0.0 - 2016-09-22

* Add support for experiments
* Add cross-validation module

## 1.0.0 - 2016-03-22

* First stable version
