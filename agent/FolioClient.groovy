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

  ProcessingMethods pm = new ProcessingMethods(this.url, this.tenant, this.user, this.password, this);

  def login() {
    def postBody = [username: this.user, password: this.password]
    println("attempt login (url=${url} / tenant=${tenant} / user=${user} / pass=${password}) ${postBody}");

    def http = configure {
      request.uri = url + '/bl-users/login'
    }

    def result = http.post {
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

  def upsertLicense(String template, String prefix, Map license) {
    println("upsertLicense(${license.reference}...)");
    def result = null;
    ensureLogin();
    try {
      Map calculatedTitleData = [
        name: license.reference,
        guid: license.globalUID,
        prefix: prefix,
        startDate: license.startDate
      ]
      String calculated_title = pm.createCalculatedNameFromTemplate(template, calculatedTitleData)

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

    Map customProperties = [:]
    try {
      customProperties = pm.makeCustomPropertyMap(prefix, license)
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

      // lookup
      def http = configure {
        request.uri = url + '/licenses/licenses'
      }

      http.post {
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
      println("FATAL ERROR: Skipping license creation: ${e.message}")
    }
    
    
    
    return result;
  }


  def updateLicense(String calculated_title, Map license, String prefix, String licenseId) {

    def result = null;
    println("updateLicense(${calculated_title}...)");
    ensureLogin();

    Map customProperties = [:]

    try {
      customProperties = pm.makeCustomPropertyMap(prefix, license)
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
      name: calculated_title,
      customProperties: customProperties,
      startDate: license.startDate,
      endDate: license.endDate
    ]

    if (statusString != null) {
      requestBody['status'] = statusString
    }

    println("License PUT Request body: ${pm.prettyPrinter(requestBody)}")

    // lookup
    def http = configure {
      request.uri = url + "/licenses/licenses/${licenseId}"
    }

    http.put {
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
      request.uri = url + '/licenses/licenses'
    }

    http.get {
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

  def createTerm(String prefix, Map property, Map category = null) {
    def result = null;
    println("createTerm(${prefix}${property.token}...)");
    ensureLogin();

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
    
    if (type == "Number") {
      type = "Decimal"

    } // TODO Date type not supported in FOLIO yet
    if (type == "Date") {
      type = "Text"
    }

    Map refdataCategory = null
    // Our process SHOULD ensure that the correct refdata category exists before creating the term
    if (type?.toLowerCase() == "refdata") {
      Map folioRefCat = lookupPickList("${prefix}${property.refdataCategory}")
      if (folioRefCat) {
        println("Found pickList: ${prefix}${property.refdataCategory}")
      } else {
        println("Warning, couldn't find pickList: ${prefix}${property.refdataCategory}")
      }
      
      refdataCategory = folioRefCat

    }

    // lookup
    def http = configure {
      request.uri = url + '/licenses/custprops'
    }

    http.post {
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
      request.uri = url + '/licenses/custprops'
    }

    http.get {
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token

      request.uri.query = [
        filters:"name==${key}",
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
      request.uri = url + '/licenses/refdata'
    }

    http.post {
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
      request.uri = url + '/licenses/refdata'
    }

    http.get {
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

    Map postMap = categoryMap
    postMap.values.push(
      [
        value: refdata.token,
        label: refdata.label_en
      ]
    )

    // lookup
    def http = configure {
      request.uri = url + "/licenses/refdata/${categoryId}"
    }

    http.put {
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
      request.uri = url + "/licenses/refdata/${categoryId}"
    }

    http.get {
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
      request.uri = url + "/${context}/refdata".toString()
    }

    http.get {
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

  def upsertSubscription(String template, String prefix, Map subscription, String folio_license_id, String folio_pkg_id = null) {
    Map calculatedTitleData = [
      name: subscription.name,
      guid: subscription.globalUID,
      prefix: prefix,
      startDate: subscription.startDate
    ]
    String calculated_title = pm.createCalculatedNameFromTemplate(template, calculatedTitleData)
    println("upsertSubscription(${calculated_title}..., ${folio_license_id}");


    def existing_subscription = lookupAgreement(subscription.globalUID)

    if ( existing_subscription ) {
      println("Located existing subscription ${existing_subscription.id} - update");
      updateAgreement(calculated_title,subscription, folio_license_id, folio_pkg_id, existing_subscription.id);
    }
    else {
      println("No subscription found - create");
      createAgreement(calculated_title,subscription, folio_license_id, folio_pkg_id);
    }
  }

  def lookupAgreement(String ref) {
    def result = null;
    println("lookupAgreement(${ref})");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url + '/erm/sas'
    }

    http.get {
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
      ArrayList periods = pm.buildPeriodList(subscription)
      Map statusMappings = pm.getAgreementStatusMap(subscription.status)
      String statusString = statusMappings.get('agreement.status')
      String reasonForClosure = null

      if (statusString == null) {
        throw new Exception ("Mapping not found for LAS:eR status ${license.status}")
      } 
      reasonForClosure = statusMappings.get('agreement.reasonForClosure')
      

      // lookup
      def http = configure {
        request.uri = url + '/erm/sas'
      }

      http.post {
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
      println("FATAL ERROR: Skipping agreement creation: ${e.message}")
    }
    
  }

  def updateAgreement(String calculated_name, Map subscription, String folio_license_id, String folio_pkg_id, String agreementId) {
    def result = null;
    println("updateAgreement(${calculated_name},${subscription.name},${folio_license_id}...)");
    ensureLogin();

    ArrayList linkedLicenses = []
    
    try {
      Map existing_controlling_license_data = pm.lookupExistingAgreementControllingLicense(subscription.globalUID)
      println("Comparing license id: ${folio_license_id} to existing controlling license link: ${existing_controlling_license_data.existingLicenseId}")
      if (existing_controlling_license_data.existingLicenseId != folio_license_id) {
        println("Existing controlling license differs from data harvested from LAS:eR--updating")
        linkedLicenses = [
          [
            id: existing_controlling_license_data.existingLinkId,
            _delete: true
          ],
          [
            remoteId:folio_license_id,
            status:'controlling'
          ]
        ]
      } else {
        println("Existing controlling license matches data harvested from LAS:eR--moving on")
      }
    } catch (Exception e) {
      println("Warning: Cannot update controlling information for agreement: ${e.message}")
    }

    ArrayList periods = []

    try {
      periods = pm.buildPeriodList(subscription)
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
      name: calculated_name,
      linkedLicenses: linkedLicenses,
      periods: periods
    ]

    if (statusString != null) {
      requestBody["agreementStatus"] = statusString
      requestBody["reasonForClosure"] = statusMappings.get('agreement.reasonForClosure')
    }

    println("Agreement PUT Request body: ${pm.prettyPrinter(requestBody)}")

    // lookup
    def http = configure {
      request.uri = url + "/erm/sas/${agreementId}"
    }

    http.put {
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
    def result = createPackage(calculated_name, pkgdata);
    
    return result;
  }

  def lookupPackage(String calculated_name, String sortTerm = 'name') {
    def result = null;
    println("lookupPackage(${calculated_name}, ${sortTerm})");
    ensureLogin();

    // lookup
    def http = configure {
      request.uri = url + '/erm/resource/electronic'
    }

    http.get {
      request.headers['X-Okapi-Tenant']=this.tenant;
      request.headers['accept']='application/json'
      request.headers['X-Okapi-Token']=session_ctx.token

      request.uri.query = [
        filters: "${sortTerm}==${calculated_name}",
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
      request.uri = url + '/erm/packages/import'
    }

    http.post {
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
