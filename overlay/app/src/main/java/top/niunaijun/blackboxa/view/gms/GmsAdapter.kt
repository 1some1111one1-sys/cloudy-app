package top.niunaijun.blackboxa.view.gms

import android.view.View
import android.view.ViewGroup
import cbfg.rvadapter.RVHolder
import cbfg.rvadapter.RVHolderFactory
import top.niunaijun.blackboxa.R
import top.niunaijun.blackboxa.bean.GmsBean
import top.niunaijun.blackboxa.databinding.ItemGmsBinding


class GmsAdapter(private val onSignIn: (GmsBean) -> Unit) : RVHolderFactory() {

    override fun createViewHolder(parent: ViewGroup?, viewType: Int, item: Any): RVHolder<out Any> {
        return GmsVH(inflate(R.layout.item_gms,parent), onSignIn)
    }


    class GmsVH(itemView:View, private val onSignIn: (GmsBean) -> Unit):RVHolder<GmsBean>(itemView){

        private val binding = ItemGmsBinding.bind(itemView)
        override fun setContent(item: GmsBean, isSelected: Boolean, payload: Any?) {
            binding.tvTitle.text = item.userName
            binding.checkbox.isChecked = item.isInstalledGms
            binding.checkbox.setOnCheckedChangeListener  { buttonView, _ ->
                if(buttonView.isPressed){
                    binding.root.performClick()
                }
            }
            // Signing in only makes sense once Google services are on for this space.
            binding.btnSignIn.visibility = if (item.isInstalledGms) View.VISIBLE else View.GONE
            binding.btnSignIn.setOnClickListener { onSignIn(item) }
        }
    }
}
