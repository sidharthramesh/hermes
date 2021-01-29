echo "Importing"
docker-compose run hermes java -jar target/hermes-full-v0.1.0.jar -d /db/snomed.db import /db/snomed
echo "Indexing"
docker-compose run hermes java -jar target/hermes-full-v0.1.0.jar -d /db/snomed.db index
# Optionsl compaction
echo "Compacting"
docker-compose run hermes java -jar target/hermes-full-v0.1.0.jar -d /db/snomed.db compact
echo "Setup complete! Run docker-compose up."