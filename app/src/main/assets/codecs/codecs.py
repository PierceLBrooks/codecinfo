
# Author: Pierce Brooks

import os
import re
import sys
import json
import shlex
import shutil
import inspect
import logging
import threading
import traceback
import subprocess

mutex = threading.Lock()
reports = []

def execute(command):
  global mutex
  global reports
  lines = []
  output = None
  if (len(command) == 0):
    mutex.acquire()
    reports.append("Command parameter population threshold failure!")
    mutex.release()
    return lines
  try:
    process = subprocess.Popen(command, env=dict(os.environ.copy()), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    while True:
      line = process.stdout.readline()
      if ((len(line) == 0) and not (process.poll() is None)):
        break
      try:
        line = line.decode("UTF-8").strip()
      except:
        continue
      lines.append(line)
    status = process.communicate()[0]
    exit = process.returncode
    if (exit == 0):
      output = status
    else:
      mutex.acquire()
      reports.append("Execution with invokation \"%s\" failure (%s)!"%tuple([shlex.join(command), str(exit)]))
      mutex.release()
  except:
    if (sys.flags.debug):
      logging.error(traceback.format_exc())
    output = []
  if ((output is None) and not (sys.flags.debug)):
    return []
  return lines

def run(target, ffmpeg):
  manifest = {}
  paths = []
  errors = []
  codex = []
  codex.append("h264")
  codex.append("hevc")
  codex.append("vp8")
  codex.append("vp9")
  codex.append("av1")
  for root, folders, files in os.walk(target):
    for name in sorted(files):
      codec = None
      success = True
      path = os.path.join(root, name)
      if ((path.endswith(".py")) or (path.endswith(".json"))):
        continue
      for i in range(len(codex)):
        if ((os.path.isfile(path+"."+codex[i]+".ivf")) and (os.path.getsize(path+"."+codex[i]+".ivf") > 0)):
          codec = codex[i]
          break
      if not (codec is None):
        if not (codec in manifest):
          manifest[codec] = []
        manifest[codec].append(os.path.basename(path)+"."+codec+".ivf")
        if (sys.flags.debug):
          print(path+"."+codec+".ivf")
        continue
      if (path.endswith(".ivf")):
        for i in range(len(codex)):
          if (path.endswith("."+codex[i]+".ivf")):
            path = path[:(len(path)-(5+len(codex[i])))]
            if not (path in paths):
              paths.append(path)
            path = None
            break
        if (path is None):
          continue
      try:
        command = "-v quiet -print_format json -show_format -show_streams".split(" ")
        command.insert(0, os.path.join(os.path.dirname(ffmpeg), "ffprobe"))
        command.append(path)
        if (sys.flags.debug):
          print(shlex.join(command))
        details = execute(command)
        details = "\n".join(details)
        if (sys.flags.debug):
          print(details)
        detail = json.loads(details)
        if ("streams" in detail):
          streams = detail["streams"]
          for i in range(len(streams)):
            stream = streams[i]
            """
            if (sys.flags.debug):
              print(str(i))
              for key in stream:
                print(str(key))
            """
            if not ("codec_name" in stream):
              continue
            if (stream["codec_name"] in codex):
              command = []
              command.append(ffmpeg)
              command.append("-i")
              command.append(path)
              command.append("-vcodec")
              command.append("copy")
              command.append(path+"."+stream["codec_name"]+".ivf")
              if (sys.flags.debug):
                print(shlex.join(command))
              if (len(execute(command)) == 0):
                errors.append(path)
                success = False
                break
              codec = stream["codec_name"]
              break
          else:
            errors.append(path)
            success = False
      except:
        logging.error(traceback.format_exc())
        errors.append(path)
        success = False
      if ((success) and (codec is None)):
        errors.append(path)
        success = False
        continue
      try:
        if ((success) and (os.path.getsize(path+"."+codec+".ivf") <= 0)):
          errors.append(path)
          success = False
      except:
        pass
      if (success):
        if not (codec in manifest):
          manifest[codec] = []
        manifest[codec].append(os.path.basename(path)+"."+codec+".ivf")
        if not (path in paths):
          paths.append(path)
  if (sys.flags.debug):
    for error in errors:
      print(str(error))
  for path in paths:
    for i in range(len(codex)):
      try:
        if (os.path.getsize(path+"."+codex[i]+".ivf") <= 0):
          os.unlink(path+"."+codex[i]+".ivf")
      except:
        pass
  if (len(manifest) > 0):
    for i in range(len(codex)):
      if not (codex[i] in manifest):
        manifest[codex[i]] = []
    descriptor = open(os.path.join(target, os.path.basename(inspect.getframeinfo(inspect.currentframe()).filename)+".json"), "w")
    descriptor.write(json.dumps(manifest))
    descriptor.close()
  return 0

def launch(arguments):
  global mutex
  global reports
  ffmpeg = None
  if (len(arguments) > 1):
    ffmpeg = arguments[1]
    if not (os.path.isfile(ffmpeg)):
      ffmpeg = None
  if (ffmpeg is None):
    try:
      ffmpeg = shutil.which("ffmpeg")
    except:
      logging.error(traceback.format_exc())
      ffmpeg = None
  if (sys.flags.debug):
    print(str(ffmpeg))
  if ((ffmpeg is None) or not (os.path.isfile(ffmpeg))):
    return False
  result = run(os.getcwd(), ffmpeg)
  print(str(result))
  if (sys.flags.debug):
    mutex.acquire()
    for report in reports:
      print(str(report))
    mutex.release()
  if not (result == 0):
    return False
  return True

if (__name__ == "__main__"):
  print(str(launch(sys.argv)))

