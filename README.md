# Crossref Event Data Stack Exchange Agent

Crossref Event Data Stackexchange agent. Monitors questions and answers on a selection of Stack Exchange sites for references to DOIs. Currently only direct DOI references, not article landing pages.

## To run

To run as an agent, `lein run`. To update the rules in Gnip, which should be one when the domain list artifact is updated, `lein run update-rules`.

## Tests

### Unit tests

 - `time docker-compose -f docker-compose-unit-tests.yml run -w /usr/src/app test lein test :unit`

## Demo

    time docker-compose -f docker-compose-unit-tests.yml run -w /usr/src/app test lein repl

## Config

 - `PERCOLATOR_URL_BASE` e.g. https://percolator.eventdata.crossref.org
 - `JWT_TOKEN`
 - `STATUS_SERVICE_BASE`
 - `ARTIFACT_BASE`, e.g. https://artifact.eventdata.crossref.org
