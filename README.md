
# Traffic System Simulation in Local Network Distributed Environment

This repository is a collection of tasks performed in Distributed Systems lab combined together effectively to *feature* a GUI for the user. It has some rough edges but the core functionality of the tasks is retained. It is a ready to run project without any requirement for installation *(Portable one may say)*. 




## Installation

There is no installation, *hehe*

```bash
  Simply download (or clone) this repository.

  Now, the biggest task. The files for individual packages ARE NOT SEPARATED. 
  Yes, I could do it but anyways. Better than breaking it apart.
```
Running it on each node is a simple task. 
```
  There are readymade batch files. 
  server.bat should be used at server node
  Traffic1_2 and Traffic3_4 are the junction roads. 
  RTO Clients could be on n nodes (I hope so).
```
### Server
```bash
  This one will ask you to put the server's IP address in the console. 
```
It was automated since java could *apparently* fetch the host node's IP address of the adapter with an active connection but *ahem* since I tested this program in Virtual Environment ~as I donot own multiple computers~ the host IP address was set as the Virtual Adapter's IP address which, let me tell you, will absolutely not work unless you find some profound literature which states the necessary sorcery. So, kindly **provide the IP address of the adapter with an active connection. Thank you.**

### Traffic Signals
```bash
  Just run and enter the server's IP address
```

### RTO Controller
```bash
  Run and enter the server's IP address.
  Here, you can attempt to change the signal status. There is mutex to prevent issues. 
```
The server identifies its connected nodes. It will not start unless the two traffic signal nodes are connected to it. You can test this locally too. Just put the host IP as localhost and run the other bat files on the same PC.
## Authors

- [@ Yash Kasle](https://www.github.com/BuildnByte)
- @ Rushikesh Mahajan
- @ Anuj Taware

and of course, myself [@ Harshal Bangar](https://github.com/StoneCollector)

And our mentor, 
- Professor Amit Nerurkar 
## Just a note
Everything stated above is solely formulated by me and I believe 'formulated' is not the most appropriate word to describe this. If anything doesn't sit right with you - just know, the people mentioned above, excluding me, have no influence on the content of this readme.


