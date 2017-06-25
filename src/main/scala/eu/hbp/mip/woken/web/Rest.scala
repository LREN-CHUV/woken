package eu.hbp.mip.woken.web

import eu.hbp.mip.woken.api.Api
import eu.hbp.mip.woken.core.{Core, CoreActors}


trait Rest extends Core with CoreActors with Api
