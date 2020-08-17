import org.apache.http.entity.mime.*
import org.apache.http.entity.mime.content.*
import org.apache.http.protocol.*
import java.nio.charset.Charset
import static groovy.json.JsonOutput.*
import groovy.util.slurpersupport.GPathResult
import org.apache.log4j.*
import java.text.SimpleDateFormat
import groovy.json.JsonOutput
import java.io.File;
import java.io.FileReader;
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import org.apache.commons.codec.binary.Hex
import static groovyx.net.http.HttpBuilder.configure
import groovyx.net.http.FromServer

import groovy.json.JsonSlurper


public class FolioClient {

  String url = null;
  String tenant = null;
  String user = null;
  String password = null;
  Map session_ctx = [:]

  public FolioClient(String url, String tenant, String user, String password) {
    this.url = url;
    this.tenant = tenant;
    this.user = user;
    this.password = password;
  }

  ProcessingMethods pm = new ProcessingMethods();

  def login() {
    def postBody = [username: this.user, password: this.password]
    println("attempt login (url=${url} / tenant=${tenant} / user=${user} / pass=${password}) ${postBody}");

    def http = configure {
      request.uri = url
    }

    def result = http.post {
      request.uri.path='/bl-users/login'
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'

      request.uri.query=[expandPermissions:false,fullPermissions:false]
      request.contentType='application/json'
      request.body=postBody

      response.failure{ FromServer fs, Object body ->
        println("Problem logging into FOLIO ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        session_ctx.auth = body
        println("OK _ headers == ${fs.getHeaders()}");
        session_ctx.token = fs.getHeaders().find { it.key.equalsIgnoreCase('x-okapi-token')}?.value
        println("LOGIN OK - TokenHeader=${session_ctx.token}");
      }

    }
  }

  def ensureLogin() {
    if ( session_ctx.auth == null )
      login()
  }

  private boolean laserInternal(String laserIsPublicValue) {
    boolean internal = true;
    if (laserIsPublicValue == "Yes") {
      internal = false;
    }
    return internal;
  }

  private String noteParagraphJoiner(String note, String paragraph) {
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

  private ArrayList buildPeriodList(Map subscription) {
    println("buildPeriodList ::${subscription.globalUID}")

    if (subscription.startDate == null) {
      throw new RuntimeException ("There is no startDate for this subscription")
    }

    def folioAgreement = lookupAgreement(subscription.globalUID)
    ArrayList periodList = []

    if (folioAgreement) {
      // We already have an agreement, so the period will need updating
      Map deleteMap = [
        id: folioAgreement.periods?.get(0)?.get('id'),
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

  private boolean comparePropertyValues(def folioProperty, def laserProperty, boolean isRefdata) {
    // This method takes in two properties, one from FOLIO and one from LAS:eR and compares several fields to check whether all the data is the same
    // It returns true if they are the same, false if they differ
    println("Comparing FOLIO and LAS:eR properties :: refdata: ${isRefdata}")

    if (folioProperty == null) {
      println("FOLIO property does not exist, so skip comparison")
      return false;
    }

    // Check values are equal
    if (isRefdata == true) {
      println("Comparing FOLIO value: ${folioProperty?.value?.value?.get(0)} to LAS:eR value: ${pm.snakeCaser(laserProperty.value)}")
      // Refdata is sent by LAS:eR in label form, so we check values in snake case
      // Also value is saved in an array of objects in FOLIO, hence value.value[0]
      if(folioProperty?.value?.value?.get(0) != pm.snakeCaser(laserProperty.value)) {
        return false;
      }
    } else {
      println("Comparing FOLIO value: ${folioProperty?.value?.get(0)} to LAS:eR value: ${laserProperty.value}")
      if (folioProperty?.value?.get(0) != laserProperty.value) {
        return false;
      }
    }

    // Check internal values match up
    // folioProperty.internal returns [bool]
    boolean folioInternal = Boolean.valueOf(folioProperty.internal?.get(0))
    boolean laserInternal = laserInternal(laserProperty.isPublic)
    println("Comparing FOLIO internal: ${folioInternal} to LAS:eR internal: ${laserInternal}")
    if (folioInternal != laserInternal) {
      return false
    }

    //Check note/paragraph fields match up
    String folioNote = folioProperty.note?.get(0)
    String laserNote = noteParagraphJoiner(laserProperty.note, laserProperty.paragraph)
    println("Comparing FOLIO note: ${folioNote} to LAS:eR note: ${laserNote}")
    if (folioNote != laserNote) {
      return false
    }

    //If everything matches then we return true
    return true
  }


  def makeCustomPropertyMap(String prefix, Map license) {
    // This method should be called inside a try/catch block
    println("makeCustomPropertyMap(${license.reference}...)");

    // check if this license already exists in FOLIO
    def folioLicense = lookupLicense(license.globalUID)

    def result = [:];

    // First port of call is to attempt to match everything coming in from the import into FOLIO
    license.properties.each({property ->
      println("PROPERTY ${property}")

      try {
        // We grab the in-FOLIO term supposedly matching the license property

        // We .toString() to avoid a weird null value
        def folioProperty = folioLicense?.customProperties?.get("${prefix}${property.name}".toString())


        // This part checks whether the term already exists on the FOLIO license.
        // If it does, we will need to delete the existing custom property information and replace with new information
        ArrayList custPropList = []
        if (folioProperty) {
          // We already have something mapped for this property -- update
          Map deleteMap = [
            id: folioProperty.id?.get(0),
            _delete: true
          ]
          custPropList.add(deleteMap)
        }

        // Lookup the value and term in FOLIO
        def term = lookupTerm("${prefix}${property.name}")
        if (term == null) {
          throw new RuntimeException("Could not find term ${prefix}${property.name}")
        }

        if (term.type == "com.k_int.web.toolkit.custprops.types.CustomPropertyRefdata") {
          // If we're in this block we're dealing with a refdata property

          boolean matches = comparePropertyValues(folioProperty, property, true)

          if (!matches) {
            println("LAS:eR property differs from what we have in FOLIO already, creating/updating")

            def refdata = lookupPickList("${prefix}${property.refdataCategory}")
            String categoryId
            if (refdata == null) {
              throw new RuntimeException("Could not find pickList ${prefix}${property.refdataCategory}")
            } else {
              categoryId = refdata.id
            }
            // lookupPickListValue doesn't actually return the list of values, it returns the category with an empty values list.
            def catValues = refdata?.values
            catValues.removeIf({value -> value.value != pm.snakeCaser(property.value)})
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

            boolean internal = laserInternal(property.isPublic)
            String internalNote = noteParagraphJoiner(property.note, property.paragraph)

            Map custPropFields = [
              internal: internal,
              note: internalNote,
              value: value,
              type: term
            ]

            custPropList.add(custPropFields)
            // Build property entry in result
            String mappingKey = "${prefix}${property.name}"
            result[mappingKey] = custPropList

            println("Added property to makeCustomPropertyMap result")

          } else {
            // If the LAS:eR license property matches what we have in FOLIO we move on
            println("LAS:eR property matches what we have in FOLIO already, skipping")
          }
          
        } else {
          // If we're in this block we're not dealing with a refdata property

          boolean matches = comparePropertyValues(folioProperty, property, false)

          if (!matches) {
            println("LAS:eR property differs from what we have in FOLIO already, creating/updating")
            boolean internal = laserInternal(property.isPublic)
            String internalNote = noteParagraphJoiner(property.note, property.paragraph)

            Map custPropFields = [
              internal: internal,
              note: internalNote,
              value: property.value,
              type: term
            ]

            custPropList.add(custPropFields)
            // Build property entry in result
            String mappingKey = "${prefix}${property.name}"
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
    })

    // Next port of call is to check every LAS:eR property in FOLIO, and ensure that still exists in the LAS:eR import
    if (folioLicense != null) {
      // If the folioLicense doesn't exist we can ignore this step
      folioLicense.customProperties.each({key, val -> 
        if (key.startsWith(prefix)) {
          // This is a laser property, check if it still exists in the import
          def laserPropertyName = key.replace(prefix, "")

          def licensePropertiesTemp = [] + license.properties
          licensePropertiesTemp.removeIf({property -> property.name != laserPropertyName})
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


  def upsertLicense(String prefix, Map license) {
    println("upsertLicense(${license.reference}...)");
    def result = null;
    ensureLogin();
    try {
      String calculated_title = "${license.reference} (${prefix}${license.globalUID})".toString()

      // Lookup license in FOLIO.
      def existing_license = lookupLicense(license.globalUID)
      if ( existing_license == null ) {
        // If it doesn't exist we create it.
        result = createLicense(calculated_title, license, prefix);
      }
      else {
        // If it does exist we update it with the newest customProperties.
        println("Located existing record for ${prefix}${license.reference} - upsert");
        result = updateLicense(calculated_title, license, prefix, existing_license.id);
      }
    }
    catch ( Exception e ) {
      println("Skip ${prefix}${license.reference} :: ${e.message}");
    }

    return result;
  }

  def createLicense(String calculated_title, Map license, String prefix) {
    def result = null;
    println("createLicense(${calculated_title}...)");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }


    Map customProperties = [:]
    try {
      customProperties = makeCustomPropertyMap(prefix, license)
    } catch (Exception e) {
      println("Warning: ${e.message}, skipping custom properties.")
    }

    Map statusMappings = [:]
    try {
      statusMappings = pm.getLicenseStatusMap(license.status)
      String statusString = statusMappings.get('license.status')

      if (statusString == null) {
        throw new Exception ("Mapping not found for LAS:eR status ${license.status}")
      }

      def requestBody = [
        name:calculated_title,
        description: "Synchronized from LAS:eR license ${license.reference}/${license.globalUID} on ${new Date()}",
        status:statusString,
        type:'consortial',
        localReference: license.globalUID,
        customProperties: customProperties,
        startDate: license.startDate,
        endDate: license.endDate
      ]

      http.post {
        request.uri.path = '/licenses/licenses'
        request.headers['X-Okapi-Tenant']=this.tenant;
        request.headers['accept']='application/json'
        request.headers['X-Okapi-Token']=session_ctx.token
        request.contentType='application/json'
        request.body = requestBody

        response.failure{ FromServer fs, Object body ->
          println("Problem in license creation ${new String(body)}");
        }

        response.success{ FromServer fs, Object body ->
          println("License POST OK");
          result = body;
        }
      }

    } catch (Exception e) {
      println("ERROR: Skipping license creation: ${e.message}")
    }
    
    
    
    return result;
  }


  def updateLicense(String calculated_title, Map license, String prefix, String licenseId) {
    def result = null;
    println("updateLicense(${calculated_title}...)");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }

    Map customProperties = [:]

    try {
      customProperties = makeCustomPropertyMap(prefix, license)
    } catch ( Exception e ) {
      println("Warning: Could not create custom property map: ${e.message}}")
    }

    String statusString = null
    Map statusMappings = [:]
    try {
      statusMappings = pm.getLicenseStatusMap(license.status)
      statusString = statusMappings.get('license.status')
      
      if (statusString == null) {
        throw new Exception ("Mapping not found for LAS:eR status ${license.status}")
      }
    } catch ( Exception e ) {
      println("Warning: Could not update status: ${e.message}}")
    }


    def requestBody = [
      customProperties: customProperties,
      startDate: license.startDate,
      endDate: license.endDate
    ]

    if (statusString != null) {
      requestBody['status'] = statusString
    }

    println("License PUT Request body: ${JsonOutput.prettyPrint(JsonOutput.toJson(requestBody))}")

    http.put {
      request.uri.path = "/licenses/licenses/${licenseId}"
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token
      request.contentType='application/json'
      request.body = requestBody

      response.failure{ FromServer fs, Object body ->
        println("Problem in license update: ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        println("License PUT OK");
        result = body;
      }

    }

    return result;
  }

  // https://folio-snapshot-okapi.aws.indexdata.com/licenses/licenses?filters=status.label%3D%3DActive&match=name&perPage=100&sort=name%3Basc&stats=true&term=American%20A
  def lookupLicense(String ref) {
    def result = null;
    println("lookupLicense(localReference:${ref})");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }

    http.get {
      request.uri.path = '/licenses/licenses'
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token

      request.uri.query = [
        filters: "localReference==${ref}",
        perPage:10,
        sort:'localReference',
        term:ref,
        stats:true
      ]

      response.failure{ FromServer fs, Object body ->
        println("Problem in license lookup: ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        switch ( body.totalRecords ) {
          case 0:
            result = null;
            break;
          case 1:
            result = body.results[0]
            break;
          default:
            throw new RuntimeException("Multiple licenses matched (${body.totalRecords}) on query for localReference:${ref}");
            break;
        }
  
      }

    }

    return result;
  }


  def upsertTerm(String prefix, Map property) {
    println("upsertTerm(${property.token}...)");
    def result = null;
    ensureLogin();

    try {
      def existing_term = lookupTerm("${prefix}${property.token}")
      if ( existing_term == null ) {
        result = createTerm(prefix, property);
      }
      else {
        println("Located existing record for ${property.token} - upsert");
        result = existing_term
      }
    }
    catch ( Exception e ) {
      println("Skip ${property.token} :: ${e.message}");
    }

    return result;
  }

  def createTerm(String prefix, Map property, Map category = null) {
    def result = null;
    println("createTerm(${prefix}${property.token}...)");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }

    boolean isNotPublic
    if (property.isPublic == "Yes") {
      isNotPublic = false
    } else {
      isNotPublic = true
    }

    //LAS:eR does not differentiate between decimal and integer, set to decimal to be sure.
    String type = property.type

    // For some reason a lot of the refdata properties don't seem to have type set explicitly -- so set if category exists
    if (property.refdataCategory) {
      type = "Refdata"
    }

    Map refdataCategory = null
    if (type == "Number") {
      type = "Decimal"
    } // TODO Date type not supported in FOLIO yet

    if (type?.toLowerCase() == "refdata") {
      Map folioRefCat = lookupPickList("${prefix}${property.refdataCategory}")
      if (folioRefCat) {
        println("Found pickList: ${prefix}${property.refdataCategory}")
      } else {
        println("Warning, couldn't find pickList: ${prefix}${property.refdataCategory}")
      }
      
      refdataCategory = folioRefCat

    }

    http.post {
      request.uri.path = '/licenses/custprops'
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token
      request.contentType='application/json'
      request.body = [
        name: new String("${prefix}${property.token}"),
        description: property.explanation_en,
        label: new String("${prefix}${property.label_en}"),
        type:property.type,
        weight: "0",
        primary: false,
        defaultInternal: isNotPublic,
        type: type,
        category: refdataCategory
      ]

      response.failure{ FromServer fs, Object body ->
        println("Problem in term creation: ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        println("Term POST OK");
        result = body;
      }

    }

    return result;
  }

  // https://folio-snapshot-okapi.aws.indexdata.com/licenses/licenses?filters=status.label%3D%3DActive&match=name&perPage=100&sort=name%3Basc&stats=true&term=American%20A
  def lookupTerm(String key) {
    def result = null;
    println("lookupTerm(${key})");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }

    http.get {
      request.uri.path = '/licenses/custprops'
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token

      request.uri.query = [
        match:'name',
        perPage:10,
        sort:'name',
        term:key,
        stats:true
      ]

      response.failure{ FromServer fs, Object body ->
        println("Problem in term lookup: ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        switch ( body.totalRecords ) {
          case 0:
            result = null;
            break;
          case 1:
            result = body.results[0]
            break;
          default:
            throw new RuntimeException("Multiple terms matched");
            break;
        }
      }
    }
    return result;
  }

  def upsertPickList(String prefix, Map refdataCat) {
    String desc = refdataCat.token
    println("upsertPickList(${desc}...)");
    def result = null;
    ensureLogin();

    try {
      def existing_pick_list = lookupPickList("${prefix}${desc}")
      if ( existing_pick_list == null ) {
        result = createPickList(prefix, refdataCat);
      }
      else {
        println("Located existing record for refdata category: ${desc} - upsert");
        result = existing_pick_list
      }
    }
    catch ( Exception e ) {
      println("Skip refdata category: ${desc} :: ${e.message}");
    }
    return result;
  }

  def createPickList(String prefix, Map refdataCat) {
    String desc = new String("${prefix}${refdataCat.token}")
    def result = null;
    println("createPickList(${desc}...)");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }

    http.post {
      request.uri.path = '/licenses/refdata'
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token
      request.contentType='application/json'
      request.body = [
        desc: desc,
        values: []
      ]

      response.failure{ FromServer fs, Object body ->
        println("Problem in pickList creation: ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        println("PickList POST OK");
        result = body;
      }

    }

    return result;
  }

  // https://folio-snapshot-okapi.aws.indexdata.com/licenses/licenses?filters=status.label%3D%3DActive&match=name&perPage=100&sort=name%3Basc&stats=true&term=American%20A
  def lookupPickList(String desc) {
    def result = null;
    println("lookupPickList(${desc})");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }

    http.get {
      request.uri.path = '/licenses/refdata'
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token

      request.uri.query = [
        filters: "desc==${desc}",
        perPage:10,
        sort:'desc',
        term:desc,
        stats:true
      ]

      response.failure{ FromServer fs, Object body ->
        println("Problem in pickList lookup: ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        switch ( body.totalRecords ) {
          case 0:
            result = null;
            break;
          case 1:
            result = body.results[0]
            break;
          default:
            throw new RuntimeException("Multiple pickLists matched");
            break;
        }
      }
    }
    return result;
  }

  def upsertPickListValue(Map refdata, String categoryId) {
    println("upsertPickListValue(${refdata.token}, ${categoryId}...)");
    def result = null;
    ensureLogin();

    try {
      def existing_pick_list_value = lookupPickListValue(refdata.token, categoryId)
      if ( existing_pick_list_value[0] == null ) {
        result = createPickListValue(refdata, categoryId, existing_pick_list_value[1]);
      }
      else {
        println("Located existing record for refdata value: ${refdata.token} - upsert");
        result = existing_pick_list_value[0]
      }
    }
    catch ( Exception e ) {
      println("Skip refdata: ${refdata.token} :: ${e.message}");
    }

    return result;
  }

  def createPickListValue(Map refdata, String categoryId, Map categoryMap) {
    def result = null;
    println("createPickListValue(${refdata.token}, ${categoryId}...)");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }

    Map postMap = categoryMap
    postMap.values.push(
      [
        value: refdata.token,
        label: refdata.label_en
      ]
    )

    http.put {
      request.uri.path = "/licenses/refdata/${categoryId}"
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token
      request.contentType='application/json'
      request.body = postMap

      response.failure{ FromServer fs, Object body ->
        println("Problem in pickListValue creation ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        println("pickListValue POST OK");
        result = body;
      }

    }

    return result;
  }

  // https://folio-snapshot-okapi.aws.indexdata.com/licenses/licenses?filters=status.label%3D%3DActive&match=name&perPage=100&sort=name%3Basc&stats=true&term=American%20A
  def lookupPickListValue(String value, String categoryId) {
    def result = [null, [:]];
    println("lookupPickListValue(${value}, ${categoryId})");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }

    http.get {
      request.uri.path = "/licenses/refdata/${categoryId}"
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token

      request.uri.query = [
        perPage:10,
        stats:true
      ]

      response.failure{ FromServer fs, Object body ->
        println("Problem in pickListValue lookup: ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        // The body response holds the values for this refdata category in a "values" map.
        ArrayList values = body.values
        values.removeIf({listValue -> listValue.value != value})

        switch ( values.size() ) {
          case 0:
            result = [null, body];
            break;
          case 1:
            result = [values[0], body]
            break;
          default:
            throw new RuntimeException("Multiple pickListValues matched");
            break;
        }
      }
    }
    return result;
  }
  
  // Cache all the reference data values we might need
  def precacheRefdata(String context) {
    def result = null;
    println("precacheRefdata()");
    ensureLogin();

    if ( session_ctx.refdata == null ) 
      session_ctx.refdata = [:]

    if ( session_ctx.refdata[context] == null )
      session_ctx.refdata[context] = [:]

    // lookup
    def http = configure {
      request.uri = url
    }

    http.get {
      request.uri.path = "/${context}/refdata".toString()
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token

      response.failure{ FromServer fs, Object body ->
        println("Problem precacheing Refdata ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        // session_ctx.refdata = body;
        body.each { rdc ->
          // println("${rdc.id} ${rdc.token}::");
          Map cat = [
            id: rdc.id,
            desc: rdc.token,
            values: [:]
          ]
          rdc.values.each { rdv ->
            // println("  --> ${rdv.id} ${rdv.value} ${rdv.label}");
            cat.values [ rdv.value ] = [ id: rdv.id, value: rdv.value, label: rdv.label]
          }
          session_ctx.refdata[context][rdc.token] = cat;
        }
      }

    }

    return result;
  }

  def upsertSubscription(String prefix, Map subscription, String folio_license_id, String folio_pkg_id = null) {
    println("upsertSubscription(${prefix}, ${subscription.globalUID}(${subscription.name})..., ${folio_license_id}");
    String calculated_name = "${subscription.name} (${prefix}${subscription.globalUID})".toString()

    def existing_subscription = lookupAgreement(subscription.globalUID)

    if ( existing_subscription ) {
      println("Located existing subscription ${existing_subscription.id} - update");
      updateAgreement(calculated_name,subscription, folio_license_id, folio_pkg_id, existing_subscription.id);
    }
    else {
      println("No subscription found - create");
      createAgreement(calculated_name,subscription, folio_license_id, folio_pkg_id);
    }
  }

  def lookupAgreement(String ref) {
    def result = null;
    println("lookupAgreement(${ref})");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }

    http.get {
      request.uri.path = '/erm/sas'
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token

      request.uri.query = [
        filters: "localReference==${ref}",
        perPage:10,
        sort:'localReference',
        term:ref,
        stats:true
      ]

      response.failure{ FromServer fs, Object body ->
        println("Problem in agreement lookup: ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        switch ( body.totalRecords ) {
          case 0:
            result = null;
            break;
          case 1:
            result = body.results[0]
            break;
          default:
            throw new RuntimeException("Multiple subscriptions matched");
            break;
        }

      }

    }

    return result;
  }

  def createAgreement(String calculated_name, Map subscription, String folio_license_id, String folio_pkg_id) {
    def result = null;
    println("createAgreement(${calculated_name},${subscription.name},${folio_license_id}...)");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }

    // We only add the custom package as an agreement line if the data from folio contained contentItems
    def items
    if (folio_pkg_id) {
      items = [
        [
          resource: [
            id: folio_pkg_id
          ]
        ]
      ]
    }

    try {
      ArrayList periods = buildPeriodList(subscription)
      Map statusMappings = pm.getAgreementStatusMap(subscription.status)
      String statusString = statusMappings.get('agreement.status')
      String reasonForClosure = null

      if (statusString == null) {
        throw new Exception ("Mapping not found for LAS:eR status ${license.status}")
      } 
      reasonForClosure = statusMappings.get('agreement.reasonForClosure')
      

      http.post {
        request.uri.path = '/erm/sas'
        request.headers['X-Okapi-Tenant']=this.tenant;
        request.headers['accept']='application/json'
        request.headers['X-Okapi-Token']=session_ctx.token
        request.contentType='application/json'
        // subscription.startDate
        request.body = [
          name:calculated_name,
          agreementStatus:statusString,
          reasonForClosure: reasonForClosure,
          description:"Imported from LAS:eR on ${new Date()}",
          localReference: subscription.globalUID,
          periods: periods,
          linkedLicenses:[
            [
              remoteId:folio_license_id,
              status:'controlling'
            ]
          ],
          items: items
        ]

        response.failure{ FromServer fs, Object body ->
          println("Problem in agreement creation: ${new String(body)}");
        }

        response.success{ FromServer fs, Object body ->
          println("Agreement POST OK");
          result = body;
        }
      }

      return result;
    } catch (Exception e) {
      println("ERROR: Skipping agreement creation: ${e.message}")
    }
    
  }

  def updateAgreement(String calculated_name, Map subscription, String folio_license_id, String folio_pkg_id, String agreementId) {
    def result = null;
    println("createAgreement(${calculated_name},${subscription.name},${folio_license_id}...)");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }

    ArrayList periods = []

    try {
      periods = buildPeriodList(subscription)
      // TODO We don't currently allow for changes in packages to make their way into FOLIO
    } catch (Exception e) {
      println("Warning: Cannot update period information for agreement: ${e.message}")
    }

    String statusString = null
    Map statusMappings = [:]
    try {
      statusMappings = pm.getAgreementStatusMap(subscription.status)
      statusString = statusMappings.get('agreement.status')

      if (statusString == null) {
        throw new Exception ("Mapping not found for LAS:eR status ${license.status}")
      }
    } catch (Exception e) {
      println("Warning: Cannot update status information for agreement: ${e.message}")
    }

    Map requestBody = [
      periods: periods
    ]

    if (statusString != null) {
      requestBody["agreementStatus"] = statusString
      requestBody["reasonForClosure"] = statusMappings.get('agreement.reasonForClosure')
    }

    println("Agreement PUT Request body: ${JsonOutput.prettyPrint(JsonOutput.toJson(requestBody))}")

    http.put {
      request.uri.path = "/erm/sas/${agreementId}"
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token
      request.contentType='application/json'
      // subscription.startDate
      request.body = requestBody;

      response.failure{ FromServer fs, Object body ->
        println("Problem in agreement update ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        println("Agreement PUT OK");
        result = body;
      }

    }

    return result;
  }

  def upsertPackage(String calculated_name, Map pkgdata) {
    println("upsertPackage(${calculated_name},...");
    //  curl -H 'X-OKAPI-TENANT: diku' -H 'Content-Type: application/json' -XPOST 'http://localhost:8080/erm/packages/import' -d '@../service/src/integration-test/resources/packages/mod-agreement-package-import-sample.json'
    def result
    def existing_package = lookupPackage(calculated_name)

    if ( existing_package ) {
      println("Located existing package ${existing_package.id} - update");
      result = existing_package
    }
    else {
      println("No package found - create");
      result = createPackage(calculated_name, pkgdata);
    }
    return result;
  }

  def lookupPackage(String calculated_name, String sortTerm = 'name') {
    def result = null;
    println("lookupPackage(${calculated_name}, ${sortTerm})");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url
    }

    http.get {
      request.uri.path = '/erm/resource/electronic'
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token

      request.uri.query = [
        match:sortTerm,
        perPage:10,
        sort:sortTerm,
        term:calculated_name,
        stats:true,
      ]

      response.failure{ FromServer fs, Object body ->
        println("Problem in package lookup: ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        switch ( body.totalRecords ) {
          case 0:
            result = null;
            break;
          case 1:
            result = body.results[0]
            break;
          default:
            throw new RuntimeException("Multiple packages matched");
            break;
        }
      }
    }

    return result;
  }

  def createPackage(String calculated_name, Map pkgdata) {
    def result = null;
    def id = null
    println("createPackage(${calculated_name}...)");

    ensureLogin();


    // lookup
    def http = configure {
      request.uri = url
    }

    http.post {
      request.uri.path = '/erm/packages/import'
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token
      request.contentType='application/json'
      // subscription.startDate
      request.body = pkgdata

      response.failure{ FromServer fs, Object body ->
        println("Problem in package creation: ${new String(body)}");
      }

      response.success{ FromServer fs, Object body ->
        println("Package POST OK");
        id = body;
      }
    }

    if (id) {
      def packageId = id.packageId
      result = lookupPackage(packageId, 'id')
    }

    return result;
  }
}
