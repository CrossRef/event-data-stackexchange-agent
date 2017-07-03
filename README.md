# Crossref Event Data Stack Exchange Agent

Crossref Event Data Stackexchange agent. Monitors questions and answers on a selection of Stack Exchange sites for references to DOIs. Currently only direct DOI references, not article landing pages.

Runs two schedules. The selection of sites in the `stackexchange-sites` Artifact is polled every 5 days. The entire list of sites (with the sites in the Artifact removed) is scanned every 30 days. Together they ensure that all data is collected, but in a way that prioritises less likely sites. If events appear in sites not in the Artifact, they can be identified in the Reports and added.

The agent goes ultra-slow on the API, throttling requests to one every few minutes.

## To run

To run as an agent, `lein run`. To update the rules in Gnip, which should be one when the domain list artifact is updated, `lein run update-rules`.

## Tests

### Unit tests

 - `time docker-compose -f docker-compose-unit-tests.yml run -w /usr/src/app test lein test :unit`

## Demo

    time docker-compose -f docker-compose-unit-tests.yml run -w /usr/src/app test lein repl

## Config

Uses Event Data global configuration namespace.

 - `STACKEXCHANGE_JWT`
 - `GLOBAL_ARTIFACT_URL_BASE`, e.g. https://artifact.eventdata.crossref.org
 - `GLOBAL_KAFKA_BOOTSTRAP_SERVERS`
 - `GLOBAL_STATUS_TOPIC`
