import groovy.grape.Grape

@GrabResolver(name='mvnRepository', root='http://central.maven.org/maven2/')
@GrabResolver(name='kint', root='http://nexus.k-int.com/content/repositories/releases')
@Grab('io.github.http-builder-ng:http-builder-ng-core:1.0.4')
@Grab('commons-codec:commons-codec:1.14')
@Grab('org.ini4j:ini4j:0.5.4')
@Grab('net.sf.opencsv:opencsv:2.3')

import org.ini4j.*;
import groovy.json.JsonOutput;

/**
 * This class controls the synchronisation of a FOLIO tenant Licenses/Agreements with a given 
 * identifier from a LAS:eR system. It uses a .ini file in $HOME/.laser/credentials. By creating different
 * sections in the ini file, users can sync several different pairs of LAS:eR / FOLIO TENANTs.
 *
 * The script works by listing all the licenses the laserIdentifier has access to, for each license in that list
 * fetching the license details, making sure a FOLIO license exists for that LAS:eR license, then iterating
 * the subscriptions attached to the LAS:eR license. For each Sub, we will upsert a FOLIO agreement and link
 * back to the source license.
 */

if ( args.length == 0 ) {
  println("usage: process.groovy {laser_cfg_name}");
  system.exit(1);
}
else {
  println("Process config ${args[0]}");
}

// Replace with args[0]
// String laser_cfg='hbz'
String laser_cfg=args[0]

Wini ini = new Wini(new File(System.getProperty("user.home")+'/.laser/credentials'));

String url = ini.get(laser_cfg, 'url', String.class);
String secret = ini.get(laser_cfg, 'secret', String.class);
String token = ini.get(laser_cfg, 'token', String.class);
String prefix = ini.get(laser_cfg, 'refprefix', String.class) ?: 'LAS:eR#';
String laserIdentifier = ini.get(laser_cfg, 'laserIdentifier', String.class);
String identifierType = ini.get(laser_cfg, 'identifierType', String.class);

String folioURL = ini.get(laser_cfg, 'folioURL', String.class)
String folioTenant = ini.get(laser_cfg, 'folioTenant', String.class)
String folioUser = ini.get(laser_cfg, 'folioUser', String.class)
String folioPass = ini.get(laser_cfg, 'folioPass', String.class)


// LaserClient hides all the detail of how to fetch [lists of] licenses and subscriptions from LAS:eR API
LaserClient lc = new LaserClient(url, secret, token, laserIdentifier, identifierType,'ZBW');

// FolioClient hides all the details of how to upsert (Create, update, find) agreements and licenses using the FOLIO API
FolioClient fc = new FolioClient(folioURL, folioTenant, folioUser, folioPass);

fc.precacheRefdata('erm')
fc.precacheRefdata('licenses')


// This is a method which allows us to go through the entries of a refdata category from LAS:eR one by one and ensure we have all the correct values
private void upsertPickListValuesOneByOne(String categoryId, Map ref, FolioClient fc) {
    if (categoryId) {
      ref.entries.each({entry ->
        fc.upsertPickListValue(entry, categoryId)
      })
    } else {
      println("Warning: no category ID found for pickList")
    }
  }


// This is a switch so that we can turn off license processing whilst working on the refdata sync
if ( 1==1 ) {

  // We grab properties and refdata from the LAS:eR endpoints
  ArrayList propertiesList = lc.getProperties()
  propertiesList.removeIf({property -> (property.scope != "License Property")})
  println("Properties from LAS:eR API: ${propertiesList}")


  ArrayList refdata = lc.getRefdata()
  println("RefData from LAS:eR API: ${refdata}")


  // This closure will do the actual "walk-the-tree" part, importing all terms and refdata needed into FOLIO
  final Closure importAllTermsAndRefdata = {final ArrayList props, final ArrayList refs, final Map<String, ?> property ->
    /* 
     * Due to the way LAS:eR stores refdata, instead of building from the top down we're going to build from the bottom up,
     * starting with each term and checking whether we need to import any refdata categories/values at each step.
     */
    
      println("Property on LAS:eR license: ${property}")
      ArrayList tempProps = [] + props
      tempProps.removeIf{tempProp -> tempProp.token != property.name}
      // Check the property exists and was passed from LAS:eR's endpoint.
      if (tempProps[0]) {
        Map laserProperty = tempProps[0]
        println("Matched property from endpoint: ${laserProperty}, looking up in FOLIO")

        // Now we check if a term with the right name exists in FOLIO
        Map folioTerm = fc.lookupTerm("${prefix}${laserProperty.token}")
        if (folioTerm != null) {
          // The term already exists in FOLIO, move on
          println("Matching term found in FOLIO")
          
        } else {
          // The term does not yet exist
          println("No matching term found in FOLIO, creating")

          // If the term is of type other than refdata or date, create immediately
          // TODO ( We don't currently have Date type custprops)
          if (laserProperty.type.toLowerCase() != "refdata" && laserProperty.type.toLowerCase() != "date") {
            
            // Create term
            fc.createTerm(prefix, laserProperty)

          } else {
            // We must check the refdata endpoint output, and from that we can cross reference refdataCategories like we did for properties
            ArrayList tempRefs = [] + refs
            tempRefs.removeIf{tempRef -> tempRef.token != laserProperty.refdataCategory}

            // Check the refdata exists and was passed from LAS:eR's endpoint.
            if (tempRefs[0]) {
              Map laserRefdataCategory = tempRefs[0]
              println("Matched refdata category from endpoint: ${laserRefdataCategory}, looking up in FOLIO")
              // Now we check if a refdata category with the right desc exists in FOLIO

              Map folioRefCat = fc.lookupPickList("${prefix}${laserRefdataCategory.token}")
              if (folioRefCat != null) {
                println("Matching refdata category found in FOLIO")
                categoryId = folioRefCat.id
                // The refdata category already exists in FOLIO.
                // Ensure all values are created for this picklist
                upsertPickListValuesOneByOne(categoryId, laserRefdataCategory, fc)
              } else {
                // No matching picklist found in FOLIO
                println("No matching picklist found in FOLIO, creating")
                
                Map folioPickList = fc.createPickList(prefix, laserRefdataCategory)
                def categoryId = folioPickList?.id
                // Then we need to ensure all values are created for this picklist
                upsertPickListValuesOneByOne(categoryId, laserRefdataCategory, fc)
              }

              // At this stage we have all of the refdata categories and values in place
              // we just need to create the term with the right category

              if (laserProperty.type.toLowerCase() != "date") {
                fc.createTerm(prefix, laserProperty, folioRefCat)
              } else {
                println("Warning, FOLIO does not yet accept custom properties with type \"Date\"")
              }

            } else {
              println("No LAS:eR Refdata category match found for ${laserProperty.refdataCategory}")
            }
          }
        }
      } else {
        println("No LAS:eR Property match found for ${property.name}")
      }
  }.curry( propertiesList, refdata );

  final Closure work = {final ArrayList props, final ArrayList refs, final Map<String, ?> licenseDef -> 
    println("Attempt to lookup licenses ${prefix}:${licenseDef.reference} / ${licenseDef.globalUID}");

    // The entry in the list has a license globalUID - use the LAS:eR API to fetch the detail record
    def license = lc.getLicense(licenseDef.globalUID);

    // Import all terms, refdata categories and refdata values needed for custom properties
    license.properties.each(importAllTermsAndRefdata)

    println("License properties: ${license.properties}")

    // Create or update a corresponding license in FOLIO
    def folio_license = fc.upsertLicense(prefix,license)

    // If the create/update worked OK
    if ( folio_license ) {
      println("  -> LAS:eR license ${licenseDef.globalUID} mapped to FOLIO ${folio_license.id}");
      // Iterate though the list of subscriptions LAS:eR has attached to this license
      license.subscriptions.each { sub_entry ->
        // For each subscription, fetch the detail (Including the title list) from LAS:eR
        println("    -> subcription ${sub_entry.globalUID}");

        // Subscriptions appear to have a successor property that we can use for linked licenses
        try {
          def subscription = lc.getSubscription(sub_entry.globalUID);

          // Write out a KBART file that represents the subscription
          // def sub_kbart = lc.generateKBART(subscription);
        
          // generate a json format file that describes the package - use the json format defined here
          // https://github.com/folio-org/mod-agreements/blob/master/service/src/integration-test/resources/packages/mod-agreement-package-import-sample.json
          String generated_package_name = new String("Pkg for ${subscription.calculatedType} ${subscription.name} (${prefix}${subscription.globalUID})")
          println("Generated Package Name ${generated_package_name}")
          def sub_pkg = lc.generateFOLIOPackageJSON(generated_package_name, subscription);
          
          // We only want to create a package here if it contains contentItems
          def folio_pkg
          if (sub_pkg?.records[0]?.contentItems.size() > 0) {
            folio_pkg = fc.upsertPackage(generated_package_name, sub_pkg);
          }
          // ...and then use a command like curl -H 'X-OKAPI-TENANT: diku' -H 'Content-Type: application/json' -XPOST 'http://localhost:8080/erm/packages/import' -d '@../service/src/integration-test/resources/packages/mod-agreement-package-import-sample.json' to upload it

          // create or update an Agreement to represent that sub, and link it to the parent LAS:eR title
          if (folio_pkg) {
            println("      -> custom package for LAS:eR ${sub_entry.globalUID} mapped to FOLIO package ${folio_pkg.id}");
            fc.upsertSubscription(prefix, subscription, folio_license.id, folio_pkg.id);
          } else {
            fc.upsertSubscription(prefix, subscription, folio_license.id);
          }
        } catch (Exception e) {
          println("FATAL ERROR: Problem processing subscription, skipping: ${e.message}")
        }
        
        
      }
    }
    else {
      println("ERROR mapping ${licenseDef.globalUID}");
    }
  }.curry( propertiesList, refdata );

  // Due to the way groovy closures work, the actual import is done at the end here.
  lc.processLicenses( work )
}
// If we're ignoring the license processing then we end up in the below bracket
 else {
   println("Warning, license processing is turned OFF")

}
// All done
