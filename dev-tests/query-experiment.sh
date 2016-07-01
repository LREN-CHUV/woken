#!/bin/bash

http -v --timeout 180 POST localhost:8087/experiment \
         variables:='[{"code":"LeftAmygdala"}]' \
         grouping:='[{"code":"COLPROT"}]' \
         covariables:='[{"code":"AGE"}]' \
         filters:='[]' \
         algorithms:='[{"code":"knn", "name": "knn", "parameters": []}]' \
         validations:='[{"code":"kfold", "name": "kfold", "parameters": [{"code": "k", "value": "2"}]}]'
