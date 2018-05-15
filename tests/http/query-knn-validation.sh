#!/bin/bash

# Test for Woken in federated mode
# Perform an experiment that learns from one dataset on node1 using kNN, then validate its results on node2.

http -v --verify=no -a admin:WoKeN --timeout 180 POST http://localhost:8087/mining/experiment \
         user:='{"code":"user1"}' \
         variables:='[{"code":"brainstem"}]' \
         grouping:='[]' \
         covariables:='[{"code":"leftcaudate"}]' \
         targetTable='cde_features_mixed' \
         algorithms:='[{"code":"knn", "name": "knn", "parameters": []}]' \
         validations:='[{"code":"kfold", "name": "kfold", "parameters": [{"code": "k", "value": "2"}]}]' \
         trainingDatasets:='[{"code":"desd-synthdata"}]' \
         validationDatasets:='[{"code":"nida-synthdata"}]'
