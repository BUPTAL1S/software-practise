include $(PJ_DIR)/build.mak
all:
	$(PJ_CXX) $(PJ_CXXFLAGS) -std=c++17 -O2 $(PROBE_SOURCE) -o $(PROBE_OUTPUT) $(PJ_LDXXFLAGS) $(PJ_LDXXLIBS) -lopenh264
