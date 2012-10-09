@echo off
@title MoopleDEV Server v83
set CLASSPATH=.;dist\*
java -Xmx1024m -Dwzpath=wz\ net.server.Server
pause