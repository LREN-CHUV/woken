package web

import api.Api
import core.{Core, CoreActors}


trait Rest extends Core with CoreActors with Api
