/*
 * Copyright IBM Corporation 2020,2021
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Driver logic that creates fault events
// - a child process parses the simulator output looking for above threshold order latency events
// - driver uses these events to continue to next step
// - a timeout is used in case latency event not received
// Steps:
// - stop random node n from N worker nodes
// - wait for recovery
// - restart node n
// - wait for long latency indicating recovery from new process, or timeout

let action = 'stop';
let enable = false;
let node = 'k3d-workernode-1-0';
let nodes = ['k3d-workernode-1-0','k3d-workernode-2-0'];
let path = require('path');
let fork = require('child_process').fork;
let spawnSync = require('child_process').spawnSync;
let timeout;
let child;

function rndnode() {
  var oneOrZero = Math.floor(Math.random() * 2);
  return nodes[oneOrZero];
}

function rndsleep() {
  sleep = 30 * Math.random();
  return 30 + Math.trunc(sleep);
}

// support testing from simulators console file
//   no waiting as simulated output will continue at constant rate
async function doit(sleep) {
  if (!process.env.FEEDFILE) {
    await new Promise(resolve => setTimeout(resolve, (sleep * 1000)));
  }
  var timestamp = new Date().toLocaleString('en-US', { hour12: false });
  console.log(timestamp+' k3d node '+action+' '+node);
  if (!process.env.FEEDFILE) {
    var doitx = spawnSync('/usr/local/bin/k3d',['node',action,node]);
    timeout = setTimeout(stopWaiting, 120*1000);
  }
  if ( action == "stop" ) {
    action = "start";
  } else {
    action = "stop";
  }
//debug  console.log("  enable message trigger");
  enable = true;
}

// Keep going if for any reason no latency event is received 
function stopWaiting() {
  console.log("Timed out waiting for recovery event. Continuing");
  if ( enable ) {
    if ( action == "stop" ) {
      // pick a new node
      node = rndnode();
    }
    // disable actiing on another message until this action completes
    enable = false;
    // stop rnd node or start last node stopped
    doit(0);
  }
}

function forkChild() {
  const parser = __dirname+'/k3d-fault-parser.js';
  const program = path.resolve(parser);
  const parameters = [5000];
  const options = {
    stdio: [ 0, 1, 2, 'ipc' ]
  };

  console.log('parent: forking child '+parser);
  child = fork(program, parameters, options);
  child.on('message', message => {
    processMessage(message);
  });

  child.on('exit', (code) => {
    console.log('simulator stream terminated with code ' + `${code}`);
    // if testing with filefeed
    if (process.env.FEEDFILE) {
      process.exit(0);
    } else {
      // sometimes kubectl logs breaks and new stream must be reestablished
      forkChild();
    }
  });
}

// process alerts from child
function processMessage(message) {
  const grepsevere = new RegExp("^.*SEVERE", "m");
  var match = grepsevere.exec(message);
  if (match) {
    console.log('special child message:', message);
    return;
  }
  if ( timeout ) {
    clearTimeout(timeout);
  }
  if ( enable ) {
    console.log('child message:', message);
    if ( action == "stop" ) {
      // pick a new node
      node = rndnode();
    }
    // disable actiing on another message until this action completes
//debug      console.log("  disable message trigger");
    enable = false;
    if (!process.env.FEEDFILE) {
      // let app run for a bit
      var sleep = rndsleep();
      console.log('  '+action+' '+node+' in '+sleep);
    }
    // stop rnd node or start last node stopped
    doit(sleep);
  } else {
    console.log('  ignoring child message:', message);
  }
}

async function main () {
  //TODO if any nodes are stopped ...
  // ... exit with message that all nodes need to be up

  // fork child parser
  forkChild();

  // first action is to stop a node
  node = rndnode();
  if (!process.env.FEEDFILE) {
    // let app run for a bit
    var sleep = rndsleep();
    console.log('  '+action+' '+node+' in '+sleep);
  }
  doit(sleep);

}

main()
