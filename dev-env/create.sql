
CREATE TABLE results_box_stat
(
  request_id character varying(32) NOT NULL,
  node character varying(32) NOT NULL,
  id numeric,
  min numeric,
  q1 numeric,
  median numeric,
  q3 numeric,
  max numeric,

  CONSTRAINT pk_results_box_stat PRIMARY KEY (request_id, node, id)
)
WITH (
  OIDS=FALSE
);
