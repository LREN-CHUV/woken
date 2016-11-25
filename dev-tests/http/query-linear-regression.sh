#!/bin/bash

http -v --timeout 180 POST localhost:8087/mining/job \
         variables:='[{"code":"cognitive_task2"}]' \
         grouping:='[]' \
         covariables:='[{"code":"score_test1"}]' \
         filters:='[]' \
         algorithm:='{"code":"linearRegression", "name": "linearRegression", "parameters": []}'
