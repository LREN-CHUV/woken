# woken: Workflow for Analytics

An orchestration platform for Docker containers running data mining algorithms.

This project exposes a web interface to execute on demand data mining algorithms defined in Docker containers and implemented using any tool or language (R, Python, Java and more are supported).

It relies on a runtime environment containing [Mesos](http://mesos.apache.org) and [Chronos])(https://mesos.github.io/chronos/) to control and execute the Docker containers over a cluster.

## Getting started

Follow these steps to get started:

1. Git-clone this repository.

        $ git clone https://github.com/LREN-CHUV/woken.git

2. Change directory into your clone:

        $ cd woken

3. Build the application

        > ./build.sh

4. Run the application

        > cd dev-tests
        > ./run.sh

5. Browse to [http://localhost:8087](http://localhost:8087/) or run one of the query* script located in folder 'dev-tests'.

dev-tests/run.sh uses docker-compose to start a full environment with Mesos, Zookeeper and Chronos, all of those are required for the proper execution of woker.

## Available Docker containers

The Docker containers that can be executed on this platform require a few specific features.

TODO: define those features - parameters passed as environment variables, in and out directories, entrypoint with a 'compute command', ...

The project [functions-repository](https://github.com/LREN-CHUV/functions-repository) contains the Docker images that can be used with woken.

## Available commands

### Mining query

Performs a data mining task.

TODO: align this API with Exareme

Path: /mining
Verb: POST

Takes a Json document in the body, returns a Json document.

Json input should be of the form:

```
  {
    "variables": [],
    "covariables": [],
    "grouping": [],
    "filters": [],
    "algorithm": ""
  }
```

where:
* variables is the list of variables
* covariables is the list of covariables
* grouping is the list of variables to group together
* filters is the list of filters
* algorithm is the algorithm to use.

Currently, the following algorithms are supported:
* data: returns the raw data matching the query
* linearRegression: performs a linear regression
* summaryStatistics: performs a summary statistics than can be used to draw box plots.

## Docker build

Execute the following commands to distribute Workflow as a Docker container:

```
  ./build.sh
  cd docker
  ./build.sh
  ./push.sh
```

## Installation

For production, woken requires Mesos and Chronos. To install them, you can use either:

* [mip-microservices-infrastructure](https://github.com/LREN-CHUV/mip-microservices-infrastructure), a collection of Ansible scripts for deployment of a full Mesos stack on Ubuntu servers.
* [mantl.io](https://github.com/CiscoCloud/mantl), a microservice infrstructure by Cisco, based on Mesos.

# What's in a name?

Woken :
* the Woken river in China - we were looking for rivers in China
* passive form of awake - it launches Docker containers and computations
* workflow - the previous name, not too different
