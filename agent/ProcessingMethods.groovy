import java.nio.charset.Charset
import static groovy.json.JsonOutput.*
import java.text.SimpleDateFormat
import groovy.json.JsonOutput
import java.io.File;
import java.io.FileReader;

import groovy.json.JsonSlurper


public class ProcessingMethods {
  // This is just a collection of methods that can be called from anywhere in the process, to help streamline the actual Client files a bit

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
  

}