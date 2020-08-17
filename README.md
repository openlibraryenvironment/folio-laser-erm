# folio-laser-erm
An stand-between agent that acts to synchronize a Laser organisation/tenant with FOLIO agreements/licenses without causing an implicit dependency in either system


# About

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
    refprefix=<A prefix that will be prepended to all imports
     into FOLIO, defaults to 'LAS:eR#'>
    folioURL=<URL of FOLIO instance>
    folioTenant=<FOLIO Tenant name>
    folioUser=<A FOLIO username>
    folioPass=<Password for the FOLIO user above>

*For now `hbz` is the only institution this code will run for, eventually this file will need to contain blocks for each institution.*

## Running
Once the setup is complete, navigate into the `spike` directory where the `process.groovy` file lives, ensure you have the latest version of the process by running a `git pull`, and run the command `groovy process.groovy`. This will begin the import process.

Warning: This process is currently only supported on Linux Systems, due to some UTF-8 encoding issues with Windows.
