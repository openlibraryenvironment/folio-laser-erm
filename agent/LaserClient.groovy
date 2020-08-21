import org.apache.http.entity.mime.*
import org.apache.http.entity.mime.content.*
import org.apache.http.protocol.*
import java.nio.charset.Charset
import static groovy.json.JsonOutput.*
import groovy.json.JsonOutput
import groovy.util.slurpersupport.GPathResult
import org.apache.log4j.*
import java.io.File;
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import org.apache.commons.codec.binary.Hex
import groovyx.net.http.HttpBuilder
import groovyx.net.http.FromServer
import static groovyx.net.http.HttpBuilder.configure

import au.com.bytecode.opencsv.CSVReader
import au.com.bytecode.opencsv.CSVWriter

import java.time.LocalDateTime
import java.time.LocalDate


public class LaserClient {

  String url = 'https://laser-qa.hbz-nrw.de'
  String secret = null;
  String token = null;
  String identifier = null;
  String identifierType = null;
  String context = null;
  File curr_dir = null;
  File prev_dir = null;

  public LaserClient(String url, String secret, String token, String identifier, String identifierType, String context='UNKNOWN') {
    this.url = url;
    this.secret = secret;
    this.token = token;
    this.identifier = identifier;
    this.identifierType = identifierType
    this.context = context;

    curr_dir = new File (pathGen(context, 'current'));
    prev_dir = new File (pathGen(context, 'previous'));

    if ( !curr_dir.exists() ) curr_dir.mkdirs();
    if ( !prev_dir.exists() ) prev_dir.mkdirs();
  }
  
  private String makeAuth(String path, String timestamp, String nonce, String q) {
    String string_to_hash = "GET${path}${timestamp}${nonce}${q}".toString()

    Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
    SecretKeySpec secret_key = new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256");
    sha256_HMAC.init(secret_key);
    return Hex.encodeHexString(sha256_HMAC.doFinal(string_to_hash.getBytes("UTF-8")));
  }

  public void processLicenses(Closure c) {
    String auth = makeAuth('/api/v0/licenseList', '', '', 'q='+identifierType+'&v='+identifier);
    println("gotAuth: ${auth}");

    def http = configure {
      request.uri = url
    }

    def result = http.get {
      request.uri.path = '/api/v0/licenseList'
      request.headers['x-authorization'] = "hmac $token:::$auth,hmac-sha256"
      request.accept = "application/json"
      request.uri.query = [
        q:identifierType,
        v:identifier
      ]
      response.when(200) { FromServer fs, Object body ->
        println("OK");
        body.each { license ->
          println("License ${JsonOutput.prettyPrint(JsonOutput.toJson(license))}")
          try {
            c.call(license)
          } catch (Exception e) {
            println("FATAL ERROR with license, skipping: ${e.message}")
          }
        }
      }
      response.when(400) { FromServer fs, Object body ->
        println("Problem processing licenses ${body}");
      }
    }
  }

  public ArrayList getProperties() {
    ArrayList properties = null;
    String auth = makeAuth('/api/v0/propertyList', '', '', '');
    println("gotAuth: ${auth}");

    def http = configure {
      request.uri = url
    }

    def result = http.get {
      request.uri.path = '/api/v0/propertyList'
      request.accept = "application/json"
      request.headers['x-authorization'] = "hmac $token:::$auth,hmac-sha256"
      response.when(200) { FromServer fs, Object body ->
        println("OK");
        properties = body
      }
      response.when(400) { FromServer fs, Object body ->
        println("Problem getting properties ${body}");
      }
    }
    return properties;
  }


  public ArrayList getRefdata() {
    ArrayList refdata = null;
    String auth = makeAuth('/api/v0/refdataList', '', '', '');
    println("gotAuth: ${auth}");

    def http = configure {
      request.uri = url
    }

    def result = http.get {
      request.uri.path = '/api/v0/refdataList'
      request.accept = "application/json"
      request.headers['x-authorization'] = "hmac $token:::$auth,hmac-sha256"
      response.when(200) { FromServer fs, Object body ->
        println("OK");
        refdata = body
      }
      response.when(400) { FromServer fs, Object body ->
        println("Problem getting refdata ${body}");
      }
    }
    return refdata;
  }

  public Map getLicense(String globalUID) {

    println("getLicense(${globalUID})");

    Map result = null;

    String auth = makeAuth('/api/v0/license', '', '', 'q=globalUID&v='+globalUID);
    println("gotAuth: ${auth}");

    def http = configure {
      request.uri = url
    }

    http.get {
      request.uri.path = '/api/v0/license'
      request.accept = "application/json"
      request.headers['x-authorization'] = "hmac $token:::$auth,hmac-sha256"
      request.uri.query = [
        q:'globalUID',
        v:globalUID
      ]
      response.when(200) { FromServer fs, Object body, Object header ->
        result = body
        File license_file = new File (pathGen(context, 'current', body.globalUID));
        if ( license_file.exists() ) {
          File old_location = new File (pathGen(context, 'previous', body.globalUID));
          old_location << license_file
        }
        // license_file << toJson(body);
        license_file << JsonOutput.prettyPrint(toJson(body));
      }
      response.when(400) { FromServer fs, Object body ->
        println("Problem getting license ${body}");
      }
    }
    return result;
  }

  public Map getSubscription(String globalUID) {
    println("getSubscription(${globalUID})");

    Map result = null;

    String auth = makeAuth('/api/v0/subscription', '', '', 'q=globalUID&v='+globalUID);
    println("gotAuth: ${auth}");

    def http = configure {
      request.uri = url
    }

    http.get {
      request.uri.path = '/api/v0/subscription'
      request.accept = "application/json"
      request.headers['x-authorization'] = "hmac $token:::$auth,hmac-sha256"
      request.uri.query = [
        q:'globalUID',
        v:globalUID
      ]
      response.when(200) { FromServer fs, Object body, Object header ->
        result = body
        File sub_file = new File (pathGen(context, 'current', body.globalUID));
        if ( sub_file.exists() ) {
          File old_location = new File (pathGen(context, 'previous', body.globalUID));
          if ( old_location.exists() )
            old_location.delete()
          old_location << sub_file
          sub_file.delete();
        }
        // license_file << toJson(body);
        sub_file << JsonOutput.prettyPrint(toJson(body));
      }
      response.when(400) { FromServer fs, Object body ->
        println("Problem getting subscription ${body}");
      }
      response.when(500) { FromServer fs, Object body ->
        println("Problem getting subscription ${new String(body)}");
      }
    }
    return result;
  }

  // Generate a tsv representing the KBART
  def generateKBART(Map subscription) {
    String file_name = pathGen(context, 'current', "${'kbart:'+subscription.globalUID}");
    CSVWriter out_writer = new CSVWriter( new OutputStreamWriter( new FileOutputStream(file_name), java.nio.charset.Charset.forName('UTF-8') ), '\t' as char)
    out_writer.writeNext((String[])(['publication_name','col','col']))
    subscription.packages.each { pkg ->
      pkg.issueEntitlements.each { ie ->
        out_writer.writeNext((String[])([ie.tipp.title.title, ie.tipp.title.medium ]))
      }
    }
    out_writer.close()
  }

  def generateFOLIOPackageJSON(String generated_package_name, String generated_file_name, Map subscription) {
    def pkg_data = [
      "header": [
         "dataSchema": [
           "name": "mod-agreements-package",
           "version": "1.0"
         ],
         "trustedSourceTI": "true"
       ],
       "records": [
       ]
    ]


    // We build a "custom package" to describe the titles controlled by this subscription - because we want the FOLIO
    // agreement to only contain a package rather than indvidual entitlements (WHich would be much closer to the LAS:eR model)
    def content_items = [];

    // Add a record foe the package
    pkg_data.records.add([
      "source": "LAS:eR",
      "reference": subscription.globalUID,
      "name": generated_package_name,
      // "packageProvider": [
      //   "name": "provider"
      // ],
      "contentItems": content_items
    ])

    // For each package (So far only ever seen one, but hey)
    subscription.packages.each { pkg ->
      // Iterate over the titles in the package and add a content item for each one
      pkg.issueEntitlements.each { ie ->

      ArrayList coverage = buildCoverageStatements(ie.coverages);

        content_items.add([
          //"depth": null,
          "accessStart": dealWithLaserDate(ie.accessStartDate),
          "accessEnd": dealWithLaserDate(ie.accessEndDate),
          "coverage": coverage,
          "platformTitleInstance": [
            "platform": ie.tipp.platform.name,
            "platform_url": ie.tipp.platform.primaryUrl,
            "url": ie.tipp.hostPlatformURL,
            "titleInstance": [
              "name": ie.tipp.title.title,
              "identifiers":ie.tipp.title.identifiers,
              "type": ie.tipp.title.medium,
              "subtype": "electronic",
            ]
          ]
        ])
      }
    }

    File pkg_file = new File (pathGen(context, 'current', generated_file_name));

    if ( pkg_file.exists() ) {
      File old_location = new File (pathGen(context, 'previous', generated_file_name));
      if ( old_location.exists() )
        old_location.delete()
      old_location << pkg_file
      pkg_file.delete();
    }

    pkg_file << JsonOutput.toJson(pkg_data);

    return pkg_data;
  }

  def pathGen(String context, String currentOrPrevious, String fileName = null) {
    // This is a windows and linux-safe path, replacing windows special key ':' with '-'
    def fs = File.separator
    def pathString = "${'.'+ fs + context + fs + currentOrPrevious}".replaceAll(":", "-")
    if (fileName) {
      pathString = pathString + "${fs + fileName}".replaceAll(":", "-")
    }

    return pathString;
  }

  private ArrayList buildCoverageStatements(ArrayList ieCoverages) {
    ArrayList coverageStatements = []
    ieCoverages.each{ ieCoverage ->

      def startDate = dealWithLaserDate(ieCoverage.startDate)
      def endDate = dealWithLaserDate(ieCoverage.endDate)
      
      Map coverageStatement = [
        startDate: startDate,
        endDate: endDate,
        startVolume: ieCoverage.startVolume,
        endVolume: ieCoverage.endVolume,
        startIssue: ieCoverage.startIssue,
        endIssue: ieCoverage.endIssue
      ]
      coverageStatements << coverageStatement
    }

    return coverageStatements;
  }

  private String dealWithLaserDate(String laserDate) {
    String dateOutput = null
    if (laserDate != null) {
      try {
        LocalDateTime laserDateLocalDateTime = LocalDateTime.parse(laserDate)
        LocalDate laserDateLocalDate = laserDateLocalDateTime.toLocalDate()
        dateOutput = laserDateLocalDate.toString()
      } catch (Exception e) {
        println("Warning: failure parsing LAS:eR Date ${laserDate}: ${e.message}")
      }
    }
    return dateOutput;
  }

}
