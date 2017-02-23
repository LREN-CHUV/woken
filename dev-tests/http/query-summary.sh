#!/bin/bash

http -v --timeout 180 POST localhost:8087/mining/job \
         variables:='[{"code":"stress_before_test1"}]' \
         grouping:='[]' \
         covariables:='[{"code":"score_test1"}]' \
         filters:='[]' \
         algorithm:='{"code":"statisticsSummary", "name": "Statistics Summary", "parameters": []}'
