import java.nio.charset.Charset
import static groovy.json.JsonOutput.*
import java.text.SimpleDateFormat
import groovy.json.JsonOutput
import java.io.File;
import java.io.FileReader;
import java.time.LocalDateTime
import java.time.LocalDate

import groovy.json.JsonSlurper

public class ProcessingMethods {
  // This is just a collection of methods that can be called from anywhere in the process, to help streamline the actual Client files a bit

  // This stuff just allows accessing of FolioClient's lookupMethods
  String url = null;
  String tenant = null;
  String user = null;
  String password = null;
  Map session_ctx = [:]
  FolioClient fc = null

  public ProcessingMethods(String url, String tenant, String user, String password, FolioClient fc) {
    this.url = url;
    this.tenant = tenant;
    this.user = user;
    this.password = password;
    this.fc = fc
  }

  // A method to JSON PrettyPrint objects
  public prettyPrinter(Map obj) {
    return JsonOutput.prettyPrint(JsonOutput.toJson(obj));
  }


  // A method to take in Strings and snake_case them.
  public String snakeCaser(String str) {
    if (str != null) {
      return str.replaceAll(" ", "_").toLowerCase();
    } else {
      return null;
    }
  }

  // Gets the mapping object from the JSON file in the spike directory
  private ArrayList getMappings() {
    def jsonSlurper = new JsonSlurper()
    Map mappings = [:]
    ArrayList result = []

    try {
      FileReader reader = new FileReader("LaserFolioMappings.json")
      //Read JSON file
      mappings = jsonSlurper.parse(reader);
      result = mappings.laserFolioMappings;


    } catch (Exception e) {
      println("Problem reading mappings file: ${e.message}")
    }
    return result;
  }

  // Gets a specific mapping object the mappings
  public Map getSpecificMappings(String key) {
    ArrayList mappingsList = getMappings()
    mappingsList.removeIf{obj -> obj[key] == null}
    switch (mappingsList.size()) {
      case 1:
        return mappingsList[0].get(key)
        break;
      case 0:
        throw new RuntimeException ("Warning: Can't find mapping for key: ${key}")
        break;
      default:
        throw new RuntimeException ("Warning: Key: ${key} matched more than one mapping object")
        break;
    } 
  }

  // Returns a map of License statuses LAS:eR -> FOLIO
  public Map getLicenseStatusMap(String laserStatus) {
    String keyString = snakeCaser(laserStatus)
    Map returnMap = [:]
    Map mapping = [:]
    try {
      mapping = getSpecificMappings('license.status')
    } catch (Exception e) {
      throw new RuntimeException (e.message)
    }

    returnMap = mapping.get(keyString)
    if (returnMap == null) {
      throw new RuntimeException ("Mapping not found for LAS:eR status \"${laserStatus}\"")
    }
    return returnMap
  }

  // Returns a map of License statuses LAS:eR -> FOLIO
  public Map getAgreementStatusMap(String laserStatus) {
    String keyString = snakeCaser(laserStatus)
    Map returnMap = [:]
    Map mapping = [:]
    try {
      mapping = getSpecificMappings('subscription.status')
    } catch (Exception e) {
      throw new RuntimeException (e.message)
    }

    returnMap = mapping.get(keyString)
    if (returnMap == null) {
      throw new RuntimeException ("Mapping not found for LAS:eR status \"${laserStatus}\"")
    }
    return returnMap
  }

  // Returns false if LAS:eR isPublic is "Yes", else true
  public boolean laserInternal(String laserIsPublicValue) {
    boolean internal = true;
    if (laserIsPublicValue == "Yes") {
      internal = false;
    }
    return internal;
  }

  // Returns LAS:eR note and paragraph data joined together with delimiter
  public String noteParagraphJoiner(String note, String paragraph) {
    // The paragraph information for custom properties will for now be stored alongside the note in FOLIO's internalNote field, with a delimiter defined below
    String delimiter = " :: "

    if (note != null && paragraph != null) {
      return note << delimiter << paragraph;
    } else if (note == null && paragraph == null) {
      return null;
    } else if (note == null) {
      return paragraph;
    } else {
      return note;
    }
  }

  // Returns a periodList for agreements based on LAS:eR subscription data
  public ArrayList buildPeriodList(Map subscription) {
    println("buildPeriodList ::${subscription.globalUID}")

    if (subscription.startDate == null) {
      throw new RuntimeException ("There is no startDate for this subscription")
    }

    def folioAgreement = fc.lookupAgreement(subscription.globalUID)
    ArrayList periodList = []

    if (folioAgreement) {
      // We already have an agreement, so the period will need updating
      Map deleteMap = [
        id: folioAgreement.periods?.getAt(0)?.get('id'),
        _delete: true
      ]
      periodList.add(deleteMap)
    }

    Map newPeriod = [
      startDate: subscription.startDate,
      endDate: subscription.endDate
    ]

    periodList.add(newPeriod)

    return periodList;
  }

  // Returns true if custom properties match on all fields, false otherwise
  public boolean comparePropertyValues(def folioProperty, def laserProperty, boolean isRefdata) {
    // This method takes in two properties, one from FOLIO and one from LAS:eR and compares several fields to check whether all the data is the same
    // It returns true if they are the same, false if they differ
    println("Comparing FOLIO and LAS:eR properties :: refdata: ${isRefdata}")

    if (folioProperty == null) {
      println("FOLIO property does not exist, so skip comparison")
      return false;
    }

    // Check values are equal
    if (isRefdata == true) {
      println("Comparing FOLIO value: ${folioProperty?.value?.value?.getAt(0)} to LAS:eR value: ${snakeCaser(laserProperty.value)}")
      // Refdata is sent by LAS:eR in label form, so we check values in snake case
      // Also value is saved in an array of objects in FOLIO, hence value.value[0]
      if(folioProperty?.value?.value?.getAt(0) != snakeCaser(laserProperty.value)) {
        return false;
      }
    } else {
      println("Comparing FOLIO value: ${folioProperty?.value?.getAt(0)} to LAS:eR value: ${laserProperty.value}")
      if (folioProperty?.value?.getAt(0) != laserProperty.value) {
        return false;
      }
    }

    //Check note/paragraph fields match up
    String folioNote = folioProperty.note?.getAt(0)
    String laserNote = noteParagraphJoiner(laserProperty.note, laserProperty.paragraph)
    println("Comparing FOLIO note: ${folioNote} to LAS:eR note: ${laserNote}")
    if (folioNote != laserNote) {
      return false
    }

    //If everything matches then we return true
    return true
  }
  
  // Returns a Map containing all custom property data from LAS:eR
  def makeCustomPropertyMap(String prefix, Map license) {

    // This method should be called inside a try/catch block
    println("makeCustomPropertyMap(${license.reference}...)");

    // check if this license already exists in FOLIO
    def folioLicense = fc.lookupLicense(license.globalUID)

    def result = [:];

    // First port of call is to attempt to match everything coming in from the import into FOLIO
    license.properties.each {property ->

      try {
        // We grab the in-FOLIO term supposedly matching the license property

        // We .toString() to avoid a weird null value
        def folioProperty = folioLicense?.customProperties?.get("${prefix}${property.token}".toString())


        // This part checks whether the term already exists on the FOLIO license.
        // If it does, we will need to delete the existing custom property information and replace with new information
        ArrayList custPropList = []

        // Slight quirk is that we actually want the `internal` part of the term to remain the same as it is in FOLIO currently,
        // so we'll do a lookup and store here
        Boolean folioInternal = null;

        if (folioProperty) {
          // We already have something mapped for this property -- update

          // Check whether there is a currently defined `internal` value, and if there is store it in folioInternal.
          Boolean currentInternal = folioProperty.internal?.getAt(0)
          if (currentInternal != null) {
            folioInternal = currentInternal
          }

          Map deleteMap = [
            id: folioProperty.id?.getAt(0),
            _delete: true
          ]
          custPropList.add(deleteMap)
        }

        // Lookup the value and term in FOLIO
        def term = fc.lookupTerm("${prefix}${property.token}")
        if (term == null) {
          throw new RuntimeException("Could not find term ${prefix}${property.token}")
        }

        if (term.type == "com.k_int.web.toolkit.custprops.types.CustomPropertyRefdata") {
          // If we're in this block we're dealing with a refdata property

          boolean matches = comparePropertyValues(folioProperty, property, true)

          if (!matches) {
            println("LAS:eR property differs from what we have in FOLIO already, creating/updating")

            def refdata = fc.lookupPickList("${prefix}${property.refdataCategory}")
            String categoryId
            if (refdata == null) {
              throw new RuntimeException("Could not find pickList ${prefix}${property.refdataCategory}")
            } else {
              categoryId = refdata.id
            }
            // lookupPickListValue doesn't actually return the list of values, it returns the category with an empty values list.
            def catValues = refdata?.values
            catValues.removeIf({value -> value.value != snakeCaser(property.value)})
            def value
            switch (catValues.size()) {
              case 1:
                value = catValues[0]
                break;
              case 0:
                break;
              default:
                throw new RuntimeException("Multiple pickListValues found matching ${property.value}")
                break;
            }
            
            if (value == null) {
              throw new RuntimeException("Could not find pickListValue ${property.value}")
            }

            String internalNote = noteParagraphJoiner(property.note, property.paragraph)
            // If there is no current internal value stored in folioInternal, default to true
            boolean internalValue = folioInternal != null ? folioInternal : true

            Map custPropFields = [
              internal: internalValue,
              note: internalNote,
              value: value,
              type: term
            ]

            custPropList.add(custPropFields)
            // Build property entry in result
            String mappingKey = "${prefix}${property.token}"
            result[mappingKey] = custPropList

            println("Added property to makeCustomPropertyMap result")

          } else {
            // If the LAS:eR license property matches what we have in FOLIO we move on
            println("LAS:eR property matches what we have in FOLIO already, skipping")
          }
          
        } else {
          // If we're in this block we're not dealing with a refdata property

          def value = property.value

          // Now we have to check for the possibility that this is secretly a Date property in LAS:eR
          if (term.type == "com.k_int.web.toolkit.custprops.types.CustomPropertyText") {
            if (property.type.toLowerCase() == "date") {
              println("Warning: This is a date type property, which is not currently supported in FOLIO--saving as ISO8601 string")
                // We parse to LocalDateTime for validation, and then back to string for storage
                LocalDateTime dateTimeValue = LocalDateTime.parse(property.value)
                LocalDate dateValue = dateTimeValue.toLocalDate()
                value = dateValue.toString()
            }
          }
          
          /* 
           * Since it's possible we changed the value to be a LocalDate instead of LocalDateTime,
           * we have to check that instead of the original property
           */
          Map propertyToCheck = property
          propertyToCheck.value = value

          boolean matches = comparePropertyValues(folioProperty, propertyToCheck, false)

          if (!matches) {
            println("LAS:eR property differs from what we have in FOLIO already, creating/updating")
            String internalNote = noteParagraphJoiner(property.note, property.paragraph)
            // If there is no current internal value stored in folioInternal, default to true
            boolean internalValue = folioInternal != null ? folioInternal : true

            Map custPropFields = [
              internal: internalValue,
              note: internalNote,
              value: value,
              type: term
            ]

            custPropList.add(custPropFields)
            // Build property entry in result
            String mappingKey = "${prefix}${property.token}"
            result[mappingKey] = custPropList

            println("Added property to makeCustomPropertyMap result")

          } else {
            // If the LAS:eR license property matches what we have in FOLIO we move on
            println("LAS:eR term matches what we have in FOLIO already, skipping")
          }
        }
      } catch (Exception e) {
        println("ERROR Skipping license property: ${e.message}")
      }
    }

    // Next port of call is to check every LAS:eR property in FOLIO, and ensure that still exists in the LAS:eR import
    if (folioLicense != null) {
      // If the folioLicense doesn't exist we can ignore this step
      folioLicense.customProperties.each({key, val -> 
        if (key.startsWith(prefix)) {
          // This is a laser property, check if it still exists in the import
          def laserPropertyName = key.replace(prefix, "")

          def licensePropertiesTemp = [] + license.properties
          licensePropertiesTemp.removeIf({property -> property.token != laserPropertyName})
          if (licensePropertiesTemp.isEmpty()) {
            // If the map is empty, that means that the LAS:eR license import does not contain this property
            println("Warning, LAS:eR import does not contain LAS:eR property: ${laserPropertyName}, removing from FOLIO")
            String valId = val[0]?.get("id")
            Map custPropFields = [                           
              id: valId,
              _delete: true
            ]
            ArrayList custPropList = [custPropFields]

            // Build property entry in result
            String mappingKey = new String("${key}")
            result[mappingKey] = custPropList
          }

        } else {
          // This is not a LAS:eR property, ignore it
          println("This is not a LAS:eR property, ignoring")
        }
      })
    }

    if (result.isEmpty()) {
      throw new RuntimeException("makeCustomPropertyMap found no customProperties")
    }

    return result;
  }

  // Returns the license link id of the current controlling license
  public Map lookupExistingAgreementControllingLicense(String globalUID) {
    def folio_agreement = fc.lookupAgreement(globalUID)
    Map result = [:]
    ArrayList linkedLicenses = folio_agreement.linkedLicenses
    linkedLicenses.removeIf{obj -> obj.status?.value != "controlling"}
    // The below should always go smoothly, since FOLIO only allows a single controlling license. If this fails then something hasd gone wrong internally in FOLIO
    switch ( linkedLicenses.size() ) {
      case 0:
        result = [existingLinkId: null, existingLicenseId: null];
        break;
      case 1:
        result = [existingLinkId: linkedLicenses[0].id, existingLicenseId: linkedLicenses[0].remoteId]
        break;
      default:
        throw new RuntimeException("Multiple agreement controlling licenses found (${linkedLicenses.size()})");
        break;
    }
    return result;
  }

  public String createCalculatedNameFromTemplate(String template, Map data = [:]) {
    String calculatedName = ""
    
    // First split the special blocks out
    String[] splitNameTemplate = template.split( /(?<=\>)|(?=\<)/ )

    splitNameTemplate.each { block ->
      // If the block is encased by <>, then check if it's one of the ones we know how to deal with
      if (block.matches( /<(.*?)\>/ )) {
        switch (block) {
          case "<startDate>":
            // We parse to LocalDateTime for validation, and then back to string--stripping time off
            LocalDateTime dateTimeValue = LocalDateTime.parse(data.startDate)
            LocalDate dateValue = dateTimeValue.toLocalDate()
            String date = dateValue.toString()
            calculatedName += (date ?: "")
            break;
          case "<prefix>":
            calculatedName += (data.prefix ?: "")
            break;
          case "<guid>":
            calculatedName += (data.guid ?: "")
            break;
          case "<name>":
            calculatedName += (data.name ?: "")
            break;
          // If none of the above, just add it on verbatim
          default:
            calculatedName += block
            break;
        }
      } else {
        calculatedName += block
      }
    }
    
    return calculatedName
  }


}