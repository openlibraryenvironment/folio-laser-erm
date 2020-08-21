# README

The process contained here is designed to pull data from LAS:eR and insert it into FOLIO, whilst sitting outside of both.

## Installation
In order to run this script, the first port of call will be to ensure groovy is installed on your machine.
It is recommended that SDKMAN! is used for this installation, and if possible for the installation/management of Java itself as well.
Instructions can be found [here](https://groovy-lang.org/install.html#SDKMAN).

Once groovy is correctly installed, you'll need to create a credentials file in a directory `.laser` on the `HOME` path. This file must be called `credentials` and have the structure:

    [hbz]
    url=<LAS:eR URL for hbz>
    secret=<LAS:eR secret>
    token=<LAS:eR token>
    laserIdentifier=<LAS:eR organisation identifier>
    identifierType=<identifier type for laserIdentifier. See LAS:eR API documentation for more information>
    refprefix=<A prefix that will be prepended to all imports into FOLIO, defaults to 'LAS:eR#'>
    nameTemplate=<A template for the naming conventions the script will adopt for imports (see below)>
    folioURL=<URL of FOLIO instance>
    folioTenant=<FOLIO Tenant name>
    folioUser=<A FOLIO username>
    folioPass=<Password for the FOLIO user above>

*For now `hbz` is the only institution this code will run for, eventually this file will need to contain blocks for each institution.*

### Templates
As you may have noticed above, there is a section in the configurations file dedicated to `nameTemplate`, allowing the user to configure a naming convention for License/Agreement/Package imports.
This is simply a string, which accepts a few special "blocks":
-  \<name> (The name of the object in LAS:eR)
- \<prefix> (The user defined prefix from the config file)
- \<guid> (The unique identifier in LAS:eR)
- \<startDate> (The startDate of the object)

The process would read a template string, say `<name> (<startDate>)`, and for an agreement called `Test` which started on `2020-01-01` would output `Test (2020-01-01)`. In theory this should be completely robust to special characters, text and numbers, including embedded angle brackets. For example `"({[<<guid>>]})"` for a guid of `"1234"` should output `"({[<1234>]})"`.

If you wish to configure different templates for differing objects, that is an option too, by using:
- licenseNameTemplate
- agreementNameTemplate
- packageNameTemplate

The general name template defaults to `'<name> (<prefix><guid>)'`, and by default licenses and agreements will use this template, and packages will prepend `"Pkg for "` to the front of the agreement name template.

## Running
Once the setup is complete, navigate into the `spike` directory where the `process.groovy` file lives, ensure you have the latest version of the process by running a `git pull`, and run the command `groovy process.groovy`. This will begin the import process.

Warning: This process is currently only supported on Linux Systems, due to some UTF-8 encoding issues with Windows.

## Reading the logs
Upon running the service will generate logs, which for the most part are just used by developers to understand what's happening. However there are a couple of things worth mentioning here, to aid in quickly finding problem areas.
There are 3 main indicators that something has gone wrong in the process:
- Warning: Either something extremely minor has failed, or there is information that might not make it into FOLIO as it is in LAS:eR. These should not hamper the process, or stop an object from being imported.
- Problem: This indicates an issue with the code connecting to an outside source, either the LAS:eR API, or the FOLIO API. A failure to read the `LaserFolioMappings.json` file will also display a `Problem` error in the logs.
- FATAL ERROR: Generally these issues indicate something is very wrong with the data being read, tending to result in the skipping of a certain license/subscription import or creation

One message in particular to watch out for is `"FATAL ERROR: Skipping agreement/license creation/update: Mapping not found for LAS:eR status "<status_name>" "`
This indicates that the mappings file is missing some information it needs in order to map some LAS:eR refdata into FOLIO, see [Mappings](#mappings).

## Mappings
This mapping file is located in the `spike` directory and is called `LaserFolioMappings.json`. It takes the form below:

```
{
  "laserFolioMappings": [
    {
      "license.status": {
        "current": {
          "license.status": "active"
        },
        "in_progress": {
          "license.status": "in_negotiation"
        },
        "retired": {
          "license.status": "expired"
        }
      }
    },
    {
      "subscription.status": {
        "current": {
          "agreement.status": "active"
        },
        "in_negotiation": {
          "agreement.status": "in_negotiation"
        },
        "expired": {
          "agreement.status": "closed",
          "agreement.reasonForClosure": "ceased"
        },
        "intended": {
          "agreement.status": "in_negotiation"
        }
      }
    }
  ]
}
```
Note in particular that we have blocks `license.status` and `subscription.status`, which correspond to the FOLIO statuses, and then these objects have keys which are snake_cased LAS:eR statuses. Finally, those objects have keys indicating what to set the FOLIO fields to, `agreement.status` or `agreement.reasonForClosure`.

What all this means is in order to add a new LAS:eR license status value of `foo`, which you want to map to FOLIO license status `bar`, you would add:

```
"foo": {
    "license.status": "bar"
}
```
into the top level `license.status` object.

Currently the only more complicated mappings supported by this process are the agreements `reasonForClosure` where the status is `closed`, but it isn't out of the question for other more complex mappings to be possible in future.
