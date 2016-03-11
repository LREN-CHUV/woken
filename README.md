# Workflow for Analytics

## Getting started

Follow these steps to get started:

1. Git-clone this repository.

        $ git clone https://github.com/LREN-CHUV/workflow.git

2. Change directory into your clone:

        $ cd workflow

3. Build the application

        > ./build.sh

4. Run the application

        > cd dev-tests
        > ./run.sh

5. Browse to [http://localhost:8087](http://localhost:8087/) or run one of the query* script located in folder 'dev-tests'.

## Docker build

Execute the following commands to distribute Workflow as a Docker container:

```
  ./build.sh
  cd docker
  ./build.sh
  ./push.sh
```

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
