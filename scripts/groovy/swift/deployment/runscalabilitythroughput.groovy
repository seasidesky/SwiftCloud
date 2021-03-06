#!/bin/bash
//usr/bin/env groovy -classpath .:scripts/groovy "$0" $@; exit $?

package swift.deployment

import static swift.deployment.SwiftYCSB.*
import static swift.deployment.Tools.*
import static swift.deployment.Topology.*;

if (args.length < 5) {
    System.err.println "usage: runycscalabilitythroughput.groovy <topology file> <workload> <mode> <opslimit> <outputdir> [#clients]"
    System.exit(1)
}

// TOPOLOGY CONFIGURATION
topologyDef = new File(args[0])
println "==== Loading topology definition from file " + topologyDef + "===="
evaluate(topologyDef)

// VARs
def workloadName = args[1]
def exp
if (workloadName.startsWith("workload-social")) {
    exp = new SwiftSocial2()
    exp.baseWorkload = SwiftSocial2.WORKLOADS[workloadName]
} else {
    exp = new SwiftYCSB()
    exp.baseWorkload = SwiftYCSB.WORKLOADS[workloadName]
}
def modeName = args[2]
exp.mode = SwiftBase.MODES[modeName]
exp.incomingOpPerSecLimit  = args[3].toInteger()
def outputDir = args[4]
exp.clients = 1000
if (args.length > 5) {
    exp.clients = args[5].toInteger()
}
if (modeName == "no-caching" && exp.incomingOpPerSecLimit > 4000) {
    exp.clients = 2500
}

// Do not compute DATABASE_TABLE_SIZE as it puts more load on the DC/clients
exp.dcReports -= 'DATABASE_TABLE_SIZE'
exp.reports -= 'DATABASE_TABLE_SIZE'
exp.runExperiment(outputDir)

System.exit(0)

