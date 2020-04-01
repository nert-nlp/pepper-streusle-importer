# Introduction

This repository contains a [Pepper](https://corpus-tools.org/pepper/) importer module for STREUSLE in `streusle-importer/`. This allows STREUSLE to be converted to any of Pepper's [supported export formats](https://corpus-tools.org/pepper/knownModules.html#exporters). 

# Conversion

Clone this repository and `cd` into it:

```
git clone https://github.com/nert-nlp/streusle-pepper-importer.git
cd streusle-pepper-importer
```

## Pepper Setup

Download [the latest stable release of Pepper](https://korpling.german.hu-berlin.de/saltnpepper/pepper/download/stable/) and unzip it:

```
wget https://korpling.german.hu-berlin.de/saltnpepper/pepper/download/stable/Pepper_2019.06.11.zip
unzip Pepper_2019.06.11.zip
```

## Importer Module Compilation
Compile the importer module.

```
cd streusle-importer
mvn package
```

Place the JAR in Pepper's `plugins` folder:

```
cp target/streusle-1.0.0-SNAPSHOT.jar ../pepper/plugins/
# return to the root repo dir
cd .. 
```

## Pepper configuration setup

Make a directory that will hold all STREUSLE-related data and copy the base Pepper 
workflow file into it.

```
mkdir pepper/streusle
cp streusle.pepper pepper/streusle/
```

By default, `streusle.pepper` specifies PAULA and ANNIS as export targets. For more
information on how to configure a Pepper workflow, see the [documentation](https://corpus-tools.org/pepper/userGuide.html#workflow_file).

## STREUSLE data prep
Prepare the enriched STREUSLE JSON and split it by document:

```bash
git clone https://github.com/nert-nlp/streusle.git
cd streusle
python conllulex2json.py streusle.conllulex > streusle.json
python govobj.py streusle.json > streusle.gov.json
python ../scripts/split_streusle_json.py streusle.gov.json ../pepper/streusle/streusle
cd ..
```

## Run Pepper Job

```
cd pepper
bash pepperStart.sh -p streusle/streusle.pepper
```

The data will be available at `streusle/out/paula` and `streusle/out/annis` on completion.

## Import into ANNIS

1. Modify the 2nd column of the last line of `streusle/out/annis/corpus.annis` so that the STREUSLE version number is included, changing it from `streusle` to e.g. `streusle_4.3`.

2. Make the same modification to the first column of all lines in `streusle/out/annis/resolver_vis_map.annis`.

3. Zip ANNIS files: `zip -r streusle.zip streusle/out/annis/`

4. Import the zip file into ANNIS using the admin interface.

5. Evaluate `scripts/resolver_vis_map.sql` to configure the visualizations.
